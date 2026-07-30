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
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
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
}
