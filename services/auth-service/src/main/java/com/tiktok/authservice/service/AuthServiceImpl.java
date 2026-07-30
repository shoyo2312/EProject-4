package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.mapper.UserMapper;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.event.user.UserRegisteredEvent;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String CLAIM_ROLE = "role";
    private static final String TOKEN_TYPE_REFRESH = "refresh";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final UserEventProducer userEventProducer;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;

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
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(request.usernameOrEmail())
                .or(() -> userRepository.findByEmailAndDeletedAtIsNull(request.usernameOrEmail()))
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        if (!HashUtils.bcryptMatches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return issueTokens(user);
    }

    @Override
    @Transactional(readOnly = true)
    public TokenResponse refresh(RefreshTokenRequest request) {
        String token = request.refreshToken();

        if (!jwtProvider.isValid(token)) {
            throw new InvalidRefreshTokenException();
        }

        Claims claims = jwtProvider.extractClaims(token);
        if (!TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE))) {
            throw new InvalidRefreshTokenException();
        }

        Long userId = Long.valueOf(claims.getSubject());
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted() && u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(InvalidRefreshTokenException::new);

        return issueTokens(user);
    }

    private TokenResponse issueTokens(User user) {
        String subject = String.valueOf(user.getId());

        String accessToken = jwtProvider.generateToken(
                subject,
                Map.of(CLAIM_ROLE, user.getRole().name()),
                jwtProperties.accessTokenExpiryMillis());

        String refreshToken = jwtProvider.generateToken(
                subject,
                Map.of(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH),
                jwtProperties.refreshTokenExpiryMillis());

        return new TokenResponse(accessToken, refreshToken, jwtProperties.accessTokenExpiryMillis());
    }
}
