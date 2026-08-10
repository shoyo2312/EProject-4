package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.config.OtpProperties;
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
import com.tiktok.authservice.entity.VerificationToken;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.event.local.EmailVerificationRequestedEvent;
import com.tiktok.authservice.event.local.PasswordResetRequestedEvent;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.EmailNotVerifiedException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.UserNotFoundException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.mapper.UserMapper;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.event.user.UserRegisteredEvent;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String CLAIM_ROLE = "role";
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
    private final VerificationTokenRepository verificationTokenRepository;
    private final OtpGenerator otpGenerator;
    private final OtpProperties otpProperties;
    private final OtpRateLimiter otpRateLimiter;
    private final ApplicationEventPublisher eventPublisher;
    private final SessionRevoker sessionRevoker;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
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

        issueOtp(saved, VerificationTokenType.EMAIL_VERIFICATION, otpProperties.emailVerificationExpiryMillis(),
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

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(HashUtils.sha256(token))
                .orElseThrow(InvalidRefreshTokenException::new);

        // Rotation without replay detection is worse than no rotation at all: the thief refreshes
        // first, the real user's next refresh fails, and all the user sees is "please sign in
        // again" while the thief keeps walking the chain forever. A rotated-but-unexpired token
        // coming back means two parties hold it and there is no way to tell them apart — so end
        // every session for that user and make both sides re-authenticate.
        if (storedToken.isReplayOfRotatedToken()) {
            log.warn("Refresh token replay detected for user {} — revoking all sessions", storedToken.getUserId());
            sessionRevoker.revokeAllSessions(storedToken.getUserId());
            throw new InvalidRefreshTokenException();
        }

        if (!storedToken.isActive()) {
            throw new InvalidRefreshTokenException();
        }

        Long userId = Long.valueOf(claims.getSubject());
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted() && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(InvalidRefreshTokenException::new);

        storedToken.rotate();
        refreshTokenRepository.save(storedToken);

        return issueTokens(user);
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
    public void verifyEmail(VerifyEmailRequest request) {
        User user = consumeOtp(PURPOSE_EMAIL_VERIFICATION, VerificationTokenType.EMAIL_VERIFICATION,
                request.email(), request.otp());

        user.markEmailVerified();
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void resendVerification(ResendVerificationRequest request) {
        otpRateLimiter.checkAllowed(PURPOSE_EMAIL_VERIFICATION, request.email());

        userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(request.email())
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> issueOtp(user, VerificationTokenType.EMAIL_VERIFICATION,
                        otpProperties.emailVerificationExpiryMillis(),
                        otp -> new EmailVerificationRequestedEvent(user.getEmail(), otp)));
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        otpRateLimiter.checkAllowed(PURPOSE_PASSWORD_RESET, request.email());

        userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(request.email())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .ifPresent(user -> issueOtp(user, VerificationTokenType.PASSWORD_RESET,
                        otpProperties.passwordResetExpiryMillis(),
                        otp -> new PasswordResetRequestedEvent(user.getEmail(), otp)));
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = consumeOtp(PURPOSE_PASSWORD_RESET, VerificationTokenType.PASSWORD_RESET,
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

    /**
     * Resolves the account, burns its one-time code, and returns the account — throwing
     * {@link InvalidOtpException} for every failure mode (unknown email, wrong code, expired
     * code, already-used code) so the response never reveals which one occurred.
     *
     * <p>Wrong guesses are counted in Redis and blocked past the limit. The counter is bumped on
     * the unknown-email path too: skipping it there would leak account existence through timing
     * and through which addresses can be probed indefinitely. Redis writes are not part of the
     * surrounding transaction, so a failure still increments even though the tx rolls back.
     */
    private User consumeOtp(String purpose, VerificationTokenType type, String email, String otp) {
        otpRateLimiter.checkGuessAllowed(purpose, email);

        try {
            User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email)
                    .orElseThrow(InvalidOtpException::new);

            VerificationToken token = verificationTokenRepository
                    .findByTokenHashAndTokenType(hashOtp(user.getId(), type, otp), type)
                    .filter(VerificationToken::isValid)
                    .orElseThrow(InvalidOtpException::new);

            token.markUsed();
            verificationTokenRepository.save(token);

            otpRateLimiter.recordGuessSuccess(purpose, email);
            return user;
        } catch (InvalidOtpException e) {
            otpRateLimiter.recordGuessFailure(purpose, email);
            throw e;
        }
    }

    private void issueOtp(User user, VerificationTokenType type, long expiryMillis,
                           Function<String, ?> eventFactory) {
        verificationTokenRepository.deleteAllByUserIdAndTokenType(user.getId(), type);

        String otp = otpGenerator.generate();
        VerificationToken token = VerificationToken.builder()
                .userId(user.getId())
                .tokenHash(hashOtp(user.getId(), type, otp))
                .tokenType(type)
                .expiresAt(Instant.now().plusMillis(expiryMillis))
                .build();
        verificationTokenRepository.save(token);

        eventPublisher.publishEvent(eventFactory.apply(otp));
    }

    /**
     * A 6-digit OTP alone has only 1e6 possible values, so hashing it in isolation would let
     * different users collide on the same token_hash. Folding in the user id + token type keeps
     * the stored hash unique per (user, purpose) even when two users are issued the same digits.
     */
    private String hashOtp(Long userId, VerificationTokenType type, String otp) {
        return HashUtils.sha256(userId + ":" + type + ":" + otp);
    }

    private TokenResponse issueTokens(User user) {
        String subject = String.valueOf(user.getId());

        String accessToken = jwtProvider.generateToken(
                subject,
                Map.of(CLAIM_ROLE, user.getRole().name(),
                        CLAIM_JTI, UUID.randomUUID().toString(),
                        JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS),
                jwtProperties.accessTokenExpiryMillis());

        String refreshToken = jwtProvider.generateToken(
                subject,
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_REFRESH,
                        CLAIM_JTI, UUID.randomUUID().toString()),
                jwtProperties.refreshTokenExpiryMillis());

        RefreshToken tokenRecord = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(HashUtils.sha256(refreshToken))
                .expiresAt(Instant.now().plusMillis(jwtProperties.refreshTokenExpiryMillis()))
                .build();
        refreshTokenRepository.save(tokenRecord);

        return new TokenResponse(accessToken, refreshToken, jwtProperties.accessTokenExpiryMillis());
    }
}
