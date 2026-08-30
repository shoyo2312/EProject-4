package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * issueOtp deletes the user's previous OTP of the same type and then inserts the new one. The
 * delete is a derived query, so it runs as em.remove() and only reaches the database at flush —
 * where Hibernate orders inserts ahead of deletes. token_hash is UNIQUE and folds the OTP in, so
 * a resend that happens to draw the digits it is replacing collides with a row that has not been
 * deleted yet, and the request fails with a 500.
 *
 * <p>One in a million per resend with a real generator, which is why the generator is stubbed to
 * always return the same digits: the collision is the point, not the odds of reaching it.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OtpReissueCollisionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @MockBean
    private OtpGenerator otpGenerator;

    @Autowired
    private AuthService authService;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void alwaysTheSameOtp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        when(otpGenerator.generate()).thenReturn("123456");
    }

    @Test
    void resendVerification_drawingTheSameOtp_replacesItInsteadOfFailing() {
        authService.register(new RegisterRequest("johndoe", "john@example.com", "password123", "test-turnstile-token"));

        assertThatCode(() -> authService.resendVerification(new ResendVerificationRequest("john@example.com", "test-turnstile-token")))
                .as("re-issuing the same digits must replace the old token, not violate its unique index")
                .doesNotThrowAnyException();

        assertThat(verificationTokenRepository.findAll())
                .as("the previous OTP is gone — one live token per user and purpose")
                .hasSize(1);
    }
}
