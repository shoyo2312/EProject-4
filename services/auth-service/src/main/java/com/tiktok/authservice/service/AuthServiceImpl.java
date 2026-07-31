package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.mapper.UserMapper;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.event.user.UserRegisteredEvent;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
    public void logout(RefreshTokenRequest request) {
        refreshTokenRepository.findByTokenHash(HashUtils.sha256(request.refreshToken()))
                .ifPresent(storedToken -> {
                    storedToken.revoke();
                    refreshTokenRepository.save(storedToken);
                });
    }

    private TokenResponse issueTokens(User user) {
        String subject = String.valueOf(user.getId());

        String accessToken = jwtProvider.generateToken(
                subject,
                Map.of(CLAIM_ROLE, user.getRole().name()),
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
