package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.AdminLoginOtpRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.MfaRequiredException;
import com.tiktok.authservice.repository.RememberedDeviceRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Admin-console login's second factor, against a real Postgres so Flyway, the ADMIN_LOGIN OTP
 * rows and the remembered_devices table are all in the loop. Not {@code @Transactional}: the OTP
 * mail rides an AFTER_COMMIT event, so the transaction has to actually commit for the code to be
 * observable.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AdminMfaServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private MailService mailService;

    @MockBean
    private TurnstileService turnstileService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private RememberedDeviceRepository rememberedDeviceRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        rememberedDeviceRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private User admin(String username, String email) {
        authService.register(new RegisterRequest(username, email, "password123", "t"));
        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElseThrow();
        user.markEmailVerified();
        user.promoteToAdmin();
        return userRepository.save(user);
    }

    /** The code from the one admin-login mail sent since the last reset. */
    private String mailedOtp(String email) {
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendAdminLoginOtp(eq(email), otp.capture());
        return otp.getValue();
    }

    @Test
    void adminLogin_withoutDeviceToken_mailsCodeAndWithholdsSession() {
        admin("adminone", "admin1@example.com");

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("adminone", "password123", "turnstile", null)))
                .isInstanceOf(MfaRequiredException.class);

        assertThat(mailedOtp("admin1@example.com")).hasSize(6);
        assertThat(verificationTokenRepository.findAll())
                .filteredOn(t -> t.getTokenType() == VerificationTokenType.ADMIN_LOGIN)
                .hasSize(1);
    }

    @Test
    void loginWithOtp_withMailedCode_returnsAWorkingSession() {
        admin("admintwo", "admin2@example.com");
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("admintwo", "password123", "turnstile", null)));
        String otp = mailedOtp("admin2@example.com");

        TokenResponse tokens = authService.loginWithOtp(
                new AdminLoginOtpRequest("admintwo", otp, "turnstile", false));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.deviceToken()).isNull();
    }

    @Test
    void loginWithOtp_wrongCode_throwsInvalidOtp() {
        admin("adminthree", "admin3@example.com");
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("adminthree", "password123", "turnstile", null)));
        mailedOtp("admin3@example.com");

        assertThatThrownBy(() -> authService.loginWithOtp(
                new AdminLoginOtpRequest("adminthree", "000000", "turnstile", false)))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void rememberedDevice_skipsTheOtpStepOnTheNextLogin() {
        admin("adminfour", "admin4@example.com");
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("adminfour", "password123", "turnstile", null)));
        String otp = mailedOtp("admin4@example.com");

        String deviceToken = authService.loginWithOtp(
                new AdminLoginOtpRequest("adminfour", otp, "turnstile", true)).deviceToken();
        assertThat(deviceToken).isNotBlank();
        assertThat(rememberedDeviceRepository.findAll()).hasSize(1);

        // Same credentials, now with the trusted-device token: straight to a session, no throw.
        TokenResponse tokens = authService.login(
                new LoginRequest("adminfour", "password123", "turnstile", deviceToken));
        assertThat(tokens.accessToken()).isNotBlank();
    }

    @Test
    void nonAdminLogin_isUnaffected() {
        authService.register(new RegisterRequest("plainuser", "user@example.com", "password123", "t"));
        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("user@example.com").orElseThrow();
        user.markEmailVerified();
        userRepository.save(user);

        TokenResponse tokens = authService.login(new LoginRequest("plainuser", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
        verify(mailService, timeout(1000).times(0)).sendAdminLoginOtp(anyString(), anyString());
    }
}
