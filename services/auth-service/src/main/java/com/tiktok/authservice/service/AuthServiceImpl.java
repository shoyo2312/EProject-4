package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.config.OtpProperties;
import com.tiktok.authservice.dto.request.AddEmailRequest;
import com.tiktok.authservice.dto.request.ForgotPasswordRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
import com.tiktok.authservice.dto.request.ResetPasswordRequest;
import com.tiktok.authservice.dto.request.VerifyEmailRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.event.local.EmailVerificationRequestedEvent;
import com.tiktok.authservice.event.local.PasswordResetRequestedEvent;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.EmailAlreadyLinkedException;
import com.tiktok.authservice.exception.EmailNotVerifiedException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.UserNotFoundException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.mapper.UserMapper;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.event.user.UserRegisteredEvent;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String CLAIM_JTI = "jti";
    private static final String PURPOSE_EMAIL_VERIFICATION = "email-verification";
    private static final String PURPOSE_PASSWORD_RESET = "password-reset";

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final UserEventProducer userEventProducer;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginRateLimiter loginRateLimiter;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final OtpProperties otpProperties;
    private final OtpService otpService;
    private final OtpRateLimiter otpRateLimiter;
    private final SessionRevoker sessionRevoker;
    private final TokenIssuer tokenIssuer;
    private final TurnstileService turnstileService;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        turnstileService.verify(request.turnstileToken());

        // Stored lowercase so every later lookup is an exact match; the username keeps the
        // casing the user chose, and only its uniqueness check is case-insensitive.
        String email = request.email().toLowerCase(Locale.ROOT);

        if (userRepository.existsByUsernameIgnoreCaseAndDeletedAtIsNull(request.username())) {
            throw new UsernameAlreadyExistsException(request.username());
        }
        if (userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        User user = User.builder()
                .username(request.username())
                .email(email)
                .passwordHash(HashUtils.bcryptHash(request.password()))
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();

        User saved = saveUnique(user, request.username(), email);

        userEventProducer.publishUserRegistered(
                UserRegisteredEvent.of(saved.getId(), saved.getUsername(), saved.getEmail()));

        otpService.issue(saved, VerificationTokenType.EMAIL_VERIFICATION, otpProperties.emailVerificationExpiryMillis(),
                otp -> new EmailVerificationRequestedEvent(saved.getEmail(), otp));

        return userMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String identifier = request.usernameOrEmail();

        // Resolved before the limit is checked, because the account is what the limit is about.
        // Keyed on the string the client sent, "bob" and "bob@example.com" were two counters for
        // one account and alternating them bought 10 attempts instead of 5.
        User user = userRepository.findByUsernameIgnoreCaseAndDeletedAtIsNull(identifier)
                .or(() -> userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(identifier))
                .orElse(null);

        String key = rateLimitKey(user, identifier);
        loginRateLimiter.checkAllowed(key);

        // Checked after the limit, so a locked-out account costs one indexed read rather than a
        // bcrypt verification per attempt — the work an attacker would otherwise get for free.
        boolean valid = user != null
                && user.getStatus() == UserStatus.ACTIVE
                // An account created by a social login has no password to match. Stated rather
                // than left to bcrypt — which does answer false for a null hash, after logging a
                // warning about it on every attempt — because "there is no password" is a fact
                // about the account, not an encoder edge case.
                && user.getPasswordHash() != null
                && HashUtils.bcryptMatches(request.password(), user.getPasswordHash());

        if (!valid) {
            loginRateLimiter.recordFailure(key);
            throw new InvalidCredentialsException();
        }

        // The password was right, so the attempt counter is cleared even if the next check
        // rejects the login — an unverified email is not a credential guess.
        loginRateLimiter.recordSuccess(key);

        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }

        return issueTokens(user);
    }

    @Override
    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        String token = request.refreshToken();

        if (!jwtProvider.isValid(token)) {
            throw new InvalidRefreshTokenException();
        }

        Claims claims = jwtProvider.extractClaims(token);
        if (!JwtProvider.TOKEN_TYPE_REFRESH.equals(claims.get(JwtProvider.CLAIM_TOKEN_TYPE))) {
            throw new InvalidRefreshTokenException();
        }

        String tokenHash = HashUtils.sha256(token);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        Instant now = Instant.now();

        // An expired row is just expired: whatever successor it may have had expired with it, so
        // there is no live chain left for a second holder to be walking.
        if (storedToken.isExpired()) {
            throw new InvalidRefreshTokenException();
        }

        // Rotation without replay detection is worse than no rotation at all: the thief refreshes
        // first, the real user's next refresh fails, and all the user sees is "please sign in
        // again" while the thief keeps walking the chain forever.
        if (storedToken.isRotated()) {
            throw rejectAsReplay(storedToken, now);
        }

        // Revoked but never rotated means logout or a password reset — no successor exists, so
        // whoever presents it is a stale client rather than a second holder of a live chain.
        if (!storedToken.isActive()) {
            throw new InvalidRefreshTokenException();
        }

        // The claim is the check. Everything above was read outside any lock and can go stale
        // between the read and here; only this UPDATE decides who rotates the token, and the
        // loser gets 0 rows back instead of quietly minting a second chain.
        if (refreshTokenRepository.claimForRotation(tokenHash, now) == 0) {
            RefreshToken claimedByOther = refreshTokenRepository.findByTokenHash(tokenHash)
                    .orElseThrow(InvalidRefreshTokenException::new);
            throw rejectAsReplay(claimedByOther, now);
        }

        // After the claim, so a token belonging to a deleted or suspended account is not rotated
        // on the way to being rejected. The throw rolls this transaction back, claim included.
        Long userId = Long.valueOf(claims.getSubject());
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted() && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(InvalidRefreshTokenException::new);

        return issueTokens(user);
    }

    /**
     * Rejects a token whose successor was already handed out, ending every session for the user
     * if the timing says theft rather than a retry.
     *
     * <p>Two parties holding one chain is indistinguishable from one party asking twice: a mobile
     * client that double-submits, or retries after a response is lost in flight, presents exactly
     * what a thief presents. The difference is when. A retry arrives while the first request is
     * still in flight; the victim's own client only refreshes when its access token runs out,
     * fifteen minutes later, and an attacker walking a stolen chain is later still. So inside the
     * grace window the loser is simply rejected — it will retry the refresh and get a fresh chain
     * — and outside it, every session for the user dies and both sides re-authenticate.
     *
     * <p>Returned rather than thrown so the call sites read as {@code throw rejectAsReplay(...)},
     * which keeps the compiler aware that control does not continue past them.
     */
    private RuntimeException rejectAsReplay(RefreshToken token, Instant now) {
        if (token.wasRotatedBefore(now.minusMillis(jwtProperties.rotationGraceMillis()))) {
            log.warn("Refresh token replay detected for user {} — revoking all sessions", token.getUserId());
            sessionRevoker.revokeAllSessions(token.getUserId());
        }
        return new InvalidRefreshTokenException();
    }

    @Override
    @Transactional
    public void logout(RefreshTokenRequest request, String accessToken) {
        revokeRefreshToken(request.refreshToken(), accessToken);
        blacklistAccessToken(accessToken);
    }

    /**
     * Revokes the presented refresh token, provided the caller has any business ending that
     * session.
     *
     * <p>The endpoint is permitAll and has to stay that way — logging out after the access token
     * has already expired is the ordinary case, not an edge one — so the refresh token itself
     * carries the authorization. Two things are checked before the row is touched:
     *
     * <ul>
     *   <li>the token is one we signed, and is a refresh token. Previously any string at all was
     *       hashed and looked up, so the endpoint would do database work for anybody who felt
     *       like sending noise at it, and an access token pasted into the field would be
     *       silently accepted as a logout that revoked nothing.</li>
     *   <li>if an access token came with it, both name the same user. Nothing here is an
     *       escalation — whoever holds someone else's refresh token can already mint sessions
     *       with it, which is strictly worse than ending one — but a request that logs out an
     *       account other than the one it authenticated as is not a logout, and refusing it
     *       gives the mismatch somewhere to be logged.</li>
     * </ul>
     *
     * <p>Silent on rejection, like the unknown-token case has always been: logout is idempotent
     * from the client's side, and an error here would tell a caller whether a given token string
     * is live.
     */
    private void revokeRefreshToken(String refreshToken, String accessToken) {
        Long owner = refreshTokenSubject(refreshToken);
        if (owner == null) {
            return;
        }

        Long bearer = accessTokenSubject(accessToken);
        if (bearer != null && !bearer.equals(owner)) {
            log.warn("Logout for user {} presented a refresh token belonging to user {} — ignored", bearer, owner);
            return;
        }

        refreshTokenRepository.findByTokenHash(HashUtils.sha256(refreshToken))
                .ifPresent(storedToken -> {
                    storedToken.revoke();
                    refreshTokenRepository.save(storedToken);
                });
    }

    /** The user a refresh token was issued to, or null if it is not a refresh token of ours. */
    private Long refreshTokenSubject(String refreshToken) {
        if (refreshToken == null || !jwtProvider.isValid(refreshToken)) {
            return null;
        }

        Claims claims = jwtProvider.extractClaims(refreshToken);
        if (!JwtProvider.TOKEN_TYPE_REFRESH.equals(claims.get(JwtProvider.CLAIM_TOKEN_TYPE))) {
            return null;
        }

        return Long.valueOf(claims.getSubject());
    }

    /** The user an access token was issued to, or null if there is no usable one on the request. */
    private Long accessTokenSubject(String accessToken) {
        if (accessToken == null || !jwtProvider.isValidAccessToken(accessToken)) {
            return null;
        }

        return Long.valueOf(jwtProvider.extractClaims(accessToken).getSubject());
    }

    /**
     * isValidAccessToken, not isValid: the blacklist is an access-token namespace, and both token
     * types are signed with the same secret, so isValid() happily accepted a refresh token
     * presented as a bearer and burned a Redis key on a jti no read side ever consults. Harmless
     * in effect, but it is exactly the call CLAUDE.md bans, five lines from the filter that gets
     * it right — and wrong code next to right code is what gets copied.
     */
    private void blacklistAccessToken(String accessToken) {
        if (accessToken == null || !jwtProvider.isValidAccessToken(accessToken)) {
            return;
        }

        Claims claims = jwtProvider.extractClaims(accessToken);
        String jti = claims.get(CLAIM_JTI, String.class);
        if (jti == null) {
            return;
        }

        Duration remaining = Duration.between(Instant.now(), claims.getExpiration().toInstant());
        accessTokenBlacklist.blacklist(jti, remaining);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(Long userId) {
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UserNotFoundException(String.valueOf(userId)));

        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void addEmail(Long userId, AddEmailRequest request) {
        turnstileService.verify(request.turnstileToken());

        String email = request.email().toLowerCase(Locale.ROOT);

        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UserNotFoundException(String.valueOf(userId)));

        // Only an account with nothing to lose may take this path. Once an address is verified,
        // replacing it is how an account gets stolen from someone who still holds the old one.
        if (user.isEmailVerified()) {
            throw new EmailAlreadyLinkedException();
        }

        // Ahead of the send budget: a request that ends in 409 sends no mail, so charging it for
        // one leaves a user who mistyped an address unable to retry with the right one.
        if (userRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        otpRateLimiter.checkAllowed(PURPOSE_EMAIL_VERIFICATION, email);

        // Stored before it is proven, exactly as a password signup stores it — which is also why
        // the check above can be trusted: an address held by an unverified account is taken.
        user.changeEmail(email);
        saveUnique(user, user.getUsername(), email);

        otpService.issue(user, VerificationTokenType.EMAIL_VERIFICATION,
                otpProperties.emailVerificationExpiryMillis(),
                otp -> new EmailVerificationRequestedEvent(email, otp));
    }

    @Override
    @Transactional
    public void verifyEmail(VerifyEmailRequest request) {
        User user = otpService.consume(PURPOSE_EMAIL_VERIFICATION, VerificationTokenType.EMAIL_VERIFICATION,
                request.email(), request.otp());

        user.markEmailVerified();
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void resendVerification(ResendVerificationRequest request) {
        turnstileService.verify(request.turnstileToken());
        otpRateLimiter.checkAllowed(PURPOSE_EMAIL_VERIFICATION, request.email());

        userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(request.email())
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> otpService.issue(user, VerificationTokenType.EMAIL_VERIFICATION,
                        otpProperties.emailVerificationExpiryMillis(),
                        otp -> new EmailVerificationRequestedEvent(user.getEmail(), otp)));
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        turnstileService.verify(request.turnstileToken());
        otpRateLimiter.checkAllowed(PURPOSE_PASSWORD_RESET, request.email());

        userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(request.email())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(user -> otpService.issue(user, VerificationTokenType.PASSWORD_RESET,
                        otpProperties.passwordResetExpiryMillis(),
                        otp -> new PasswordResetRequestedEvent(user.getEmail(), otp)));
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = otpService.consume(PURPOSE_PASSWORD_RESET, VerificationTokenType.PASSWORD_RESET,
                request.email(), request.otp());

        user.changePasswordHash(HashUtils.bcryptHash(request.newPassword()));
        userRepository.save(user);

        // Revoking refresh tokens alone left the attacker's already-issued access token working
        // until it expired — the reset is usually done *because* the account is suspected stolen,
        // so the whole point is to cut access now.
        sessionRevoker.revokeAllSessions(user.getId());
    }

    /**
     * Which counter this attempt spends. One per account, so every way of naming the same account
     * shares a budget; unknown accounts fall back to the identifier itself, because leaving them
     * unlimited would make "never blocked" mean "no such account" — a cleaner existence oracle
     * than the one it replaces.
     *
     * <p>The prefixes keep the two namespaces apart: usernames have no character restriction, so
     * without them someone could register the literal name {@code user:123} and spend the real
     * user 123's budget. It also survives a rename — the id does not change, the username can.
     *
     * <p>ponytail: an attacker who exhausts "bob" and then sees "bob@example.com" already blocked
     * has learned the two name one account. That is inherent — limiting an account across both
     * forms requires knowing they are the same account. Accepted: a brute-force ceiling that
     * actually holds beats hiding a link the attacker had to guess correctly to test.
     */
    private String rateLimitKey(User user, String identifier) {
        return user != null ? "user:" + user.getId() : "unknown:" + identifier;
    }

    /**
     * Persists the new account, converting a lost uniqueness race into the same 409 the
     * sequential case returns.
     *
     * <p>The existsBy checks in {@link #register} are not the guarantee: two concurrent
     * registrations for the same identity both read nothing and both go on to insert, and only
     * uq_users_email / uq_users_username stops the second. The id is assigned rather than
     * generated, so Hibernate defers the INSERT to flush — left to {@code save()} the violation
     * surfaced at commit, after this method had returned and outside any catch, and the loser of
     * a race got a 500 for the action that answers 409 when it loses by a second instead of a
     * millisecond. Flushing here pulls the failure back inside. Same shape as user-service's
     * {@code FollowServiceImpl.follow}.
     */
    private User saveUnique(User user, String username, String email) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateIdentity(ex, username, email);
        }
    }

    /**
     * Which of the two unique indexes was hit. Postgres names the offending index in its 23505
     * error and Hibernate surfaces that as the constraint name; without it there is no way to
     * tell the loser of a username race from the loser of an email race, and naming the wrong
     * field sends the user off to change the input that was fine.
     *
     * <p>An unrecognised constraint is rethrown as-is. That is a schema change nobody taught this
     * method about — a bug on our side, not a conflict the caller can fix, so it belongs in the
     * 500 bucket with a stack trace rather than being guessed into a 409.
     */
    private RuntimeException duplicateIdentity(DataIntegrityViolationException ex, String username, String email) {
        String constraint = ex.getCause() instanceof ConstraintViolationException cause
                ? String.valueOf(cause.getConstraintName()).toLowerCase(Locale.ROOT)
                : "";

        if (constraint.contains("username")) {
            return new UsernameAlreadyExistsException(username);
        }
        if (constraint.contains("email")) {
            return new EmailAlreadyExistsException(email);
        }
        return ex;
    }

    private TokenResponse issueTokens(User user) {
        return tokenIssuer.issue(user);
    }
}
