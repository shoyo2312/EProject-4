package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.TooManyLoginAttemptsException;
import com.tiktok.authservice.exception.UserNotFoundException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises AuthServiceImpl against a real Postgres (Testcontainers) instead of mocking
 * the repository layer, so Flyway migrations, unique constraints, and query derivation
 * are verified along with the business logic. Kafka is mocked out — the outbox row is
 * what matters here, not actual broker delivery.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AuthServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        loginRateLimiter.reset();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private RegisterRequest validRegisterRequest() {
        return new RegisterRequest("johndoe", "john@example.com", "password123");
    }

    @Test
    @Transactional
    void register_persistsUserWithHashedPasswordAndOutboxEvent() {
        UserResponse response = authService.register(validRegisterRequest());

        assertThat(response.username()).isEqualTo("johndoe");
        assertThat(response.email()).isEqualTo("john@example.com");

        User saved = userRepository.findById(response.id()).orElseThrow();
        assertThat(saved.getPasswordHash()).isNotEqualTo("password123");
        assertThat(HashUtils.bcryptMatches("password123", saved.getPasswordHash())).isTrue();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);

        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getAggregateId)
                .containsExactly(String.valueOf(saved.getId()));
    }

    @Test
    @Transactional
    void register_duplicateUsername_throwsConflict() {
        authService.register(validRegisterRequest());

        RegisterRequest duplicate = new RegisterRequest("johndoe", "other@example.com", "password123");

        assertThatThrownBy(() -> authService.register(duplicate))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    @Transactional
    void register_duplicateEmail_throwsConflict() {
        authService.register(validRegisterRequest());

        RegisterRequest duplicate = new RegisterRequest("janedoe", "john@example.com", "password123");

        assertThatThrownBy(() -> authService.register(duplicate))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    @Transactional
    void login_withValidUsernameAndPassword_returnsTokens() {
        authService.register(validRegisterRequest());

        TokenResponse tokens = authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(jwtProvider.isValid(tokens.accessToken())).isTrue();
    }

    @Test
    @Transactional
    void login_withEmailInsteadOfUsername_returnsTokens() {
        authService.register(validRegisterRequest());

        TokenResponse tokens = authService.login(new LoginRequest("john@example.com", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
    }

    @Test
    @Transactional
    void login_withWrongPassword_throwsInvalidCredentials() {
        authService.register(validRegisterRequest());

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpass")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @Transactional
    void login_withUnknownUser_throwsInvalidCredentials() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "password123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @Transactional
    void login_withLockedUser_throwsInvalidCredentials() {
        UserResponse registered = authService.register(validRegisterRequest());
        User user = userRepository.findById(registered.id()).orElseThrow();
        user.lock();
        userRepository.save(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "password123")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @Transactional
    void refresh_withValidRefreshToken_issuesNewTokens() {
        authService.register(validRegisterRequest());
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        TokenResponse refreshed = authService.refresh(new RefreshTokenRequest(initial.refreshToken()));

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(jwtProvider.isValid(refreshed.accessToken())).isTrue();
    }

    @Test
    @Transactional
    void refresh_withAccessTokenInsteadOfRefreshToken_throwsInvalidRefreshToken() {
        authService.register(validRegisterRequest());
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(initial.accessToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Transactional
    void refresh_withGarbageToken_throwsInvalidRefreshToken() {
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("not-a-jwt")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Transactional
    void refresh_reusingRotatedToken_throwsInvalidRefreshToken() {
        authService.register(validRegisterRequest());
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.refresh(new RefreshTokenRequest(initial.refreshToken()));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(initial.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Transactional
    void logout_revokesRefreshToken() {
        authService.register(validRegisterRequest());
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(initial.refreshToken()), null);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(initial.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Transactional
    void logout_withUnknownToken_doesNotThrow() {
        authService.logout(new RefreshTokenRequest("unknown-token"), null);
    }

    @Test
    @Transactional
    void logout_withAccessToken_blacklistsItsJti() {
        authService.register(validRegisterRequest());
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(initial.refreshToken()), initial.accessToken());

        String jti = jwtProvider.extractClaims(initial.accessToken()).get("jti", String.class);
        assertThat(jti).isNotBlank();
        assertThat(redisTemplate.hasKey(AccessTokenBlacklist.KEY_PREFIX + jti)).isTrue();
    }

    @Test
    @Transactional
    void logout_withoutAccessToken_doesNotBlacklistAnything() {
        authService.register(validRegisterRequest());
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(initial.refreshToken()), null);

        String jti = jwtProvider.extractClaims(initial.accessToken()).get("jti", String.class);
        assertThat(redisTemplate.hasKey(AccessTokenBlacklist.KEY_PREFIX + jti)).isFalse();
    }

    @Test
    @Transactional
    void login_afterFiveFailedAttempts_locksOutEvenWithCorrectPassword() {
        authService.register(validRegisterRequest());

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpass")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "password123")))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @Transactional
    void login_successResetsFailedAttemptCounter() {
        authService.register(validRegisterRequest());

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpass")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        authService.login(new LoginRequest("johndoe", "password123"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpass")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @Transactional
    void getCurrentUser_withExistingUser_returnsUserResponse() {
        UserResponse registered = authService.register(validRegisterRequest());

        UserResponse me = authService.getCurrentUser(registered.id());

        assertThat(me.id()).isEqualTo(registered.id());
        assertThat(me.username()).isEqualTo("johndoe");
        assertThat(me.email()).isEqualTo("john@example.com");
    }

    @Test
    @Transactional
    void getCurrentUser_withUnknownId_throwsUserNotFound() {
        assertThatThrownBy(() -> authService.getCurrentUser(999999L))
                .isInstanceOf(UserNotFoundException.class);
    }
}
