package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.config.OtpProperties;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
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
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String CLAIM_ROLE = "role";
    private static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_JTI = "jti";

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

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsernameAndDeletedAtIsNull(request.username())) {
            throw new UsernameAlreadyExistsException(request.username());
        }
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(HashUtils.bcryptHash(request.password()))
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();

        User saved = userRepository.save(user);

        userEventProducer.publishUserRegistered(
                UserRegisteredEvent.of(saved.getId(), saved.getUsername(), saved.getEmail()));

        issueOtp(saved, VerificationTokenType.EMAIL_VERIFICATION, otpProperties.emailVerificationExpiryMillis(),
                otp -> new EmailVerificationRequestedEvent(saved.getEmail(), otp));

        return userMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String key = request.usernameOrEmail();
        loginRateLimiter.checkAllowed(key);

        User user = userRepository.findByUsernameAndDeletedAtIsNull(key)
                .or(() -> userRepository.findByEmailAndDeletedAtIsNull(key))
                .orElse(null);

        boolean valid = user != null
                && user.getStatus() == UserStatus.ACTIVE
                && HashUtils.bcryptMatches(request.password(), user.getPasswordHash());

        if (!valid) {
            loginRateLimiter.recordFailure(key);
            throw new InvalidCredentialsException();
        }

        loginRateLimiter.recordSuccess(key);
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
        if (!TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE))) {
            throw new InvalidRefreshTokenException();
        }

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(HashUtils.sha256(token))
                .filter(RefreshToken::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);

        Long userId = Long.valueOf(claims.getSubject());
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted() && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(InvalidRefreshTokenException::new);

        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        return issueTokens(user);
    }

    @Override
    @Transactional
    public void logout(RefreshTokenRequest request, String accessToken) {
        refreshTokenRepository.findByTokenHash(HashUtils.sha256(request.refreshToken()))
                .ifPresent(storedToken -> {
                    storedToken.revoke();
                    refreshTokenRepository.save(storedToken);
                });

        blacklistAccessToken(accessToken);
    }

    private void blacklistAccessToken(String accessToken) {
        if (accessToken == null || !jwtProvider.isValid(accessToken)) {
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
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email())
                .orElseThrow(InvalidOtpException::new);

        VerificationToken token = verificationTokenRepository
                .findByTokenHashAndTokenType(hashOtp(user.getId(), VerificationTokenType.EMAIL_VERIFICATION, request.otp()),
                        VerificationTokenType.EMAIL_VERIFICATION)
                .filter(VerificationToken::isValid)
                .orElseThrow(InvalidOtpException::new);

        token.markUsed();
        verificationTokenRepository.save(token);

        user.markEmailVerified();
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void resendVerification(ResendVerificationRequest request) {
        otpRateLimiter.checkAllowed("email-verification", request.email());

        userRepository.findByEmailAndDeletedAtIsNull(request.email())
                .filter(user -> !user.isEmailVerified())
                .ifPresent(user -> issueOtp(user, VerificationTokenType.EMAIL_VERIFICATION,
                        otpProperties.emailVerificationExpiryMillis(),
                        otp -> new EmailVerificationRequestedEvent(user.getEmail(), otp)));
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
                Map.of(CLAIM_ROLE, user.getRole().name(), CLAIM_JTI, UUID.randomUUID().toString()),
                jwtProperties.accessTokenExpiryMillis());

        String refreshToken = jwtProvider.generateToken(
                subject,
                Map.of(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH, CLAIM_JTI, UUID.randomUUID().toString()),
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
