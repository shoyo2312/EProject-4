package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.CompleteSignupRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.StartSignupRequest;
import com.tiktok.authservice.dto.request.VerifySignupRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.InvalidSignupTokenException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The email-first signup — {@code /register/start}, {@code /register/verify},
 * {@code /register/complete} — against real Postgres and Redis, like {@link AuthServiceImplTest}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class EmailFirstSignupTest {

    private static final String EMAIL = "new@example.com";
    private static final String TURNSTILE = "test-turnstile-token";

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private MailService mailService;

    @MockitoBean
    private TurnstileService turnstileService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private AuthSecurityEventRepository securityEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        securityEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        reset(mailService);
    }

    /** Starts a signup and returns the code that was mailed for it. */
    private String startAndReadCode(String email) {
        authService.startSignup(new StartSignupRequest(email, TURNSTILE, null));
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendVerificationOtp(eq(email), otp.capture());
        return otp.getValue();
    }

    private String ticketFor(String email) {
        String otp = startAndReadCode(email);
        return authService.verifySignup(new VerifySignupRequest(email, otp)).signupToken();
    }

    @Test
    void fullFlow_createsVerifiedAccountAndSignsIn() {
        String ticket = ticketFor(EMAIL);

        TokenResponse tokens = authService.completeSignup(
                new CompleteSignupRequest(ticket, "newcomer", "Password1!"));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();

        User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(EMAIL).orElseThrow();
        assertThat(user.getUsername()).isEqualTo("newcomer");
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(outboxEventRepository.findAll()).hasSize(1);

        // The password set at the end is the one that logs in.
        assertThat(authService.login(new LoginRequest("newcomer", "Password1!")).accessToken())
                .isNotBlank();
    }

    @Test
    void start_withTakenEmail_isRefusedAndMailsNothing() {
        authService.register(new RegisterRequest("existing", EMAIL, "Password1!", TURNSTILE));
        verify(mailService, timeout(2000)).sendVerificationOtp(eq(EMAIL), anyString());
        reset(mailService);

        assertThatThrownBy(() -> authService.startSignup(new StartSignupRequest("NEW@example.com", TURNSTILE, null)))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(mailService, after(500).never()).sendVerificationOtp(anyString(), anyString());
    }

    @Test
    void verify_withWrongCode_isRefused() {
        startAndReadCode(EMAIL);

        assertThatThrownBy(() -> authService.verifySignup(new VerifySignupRequest(EMAIL, "000000")))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void verify_codeIsSingleUse() {
        String otp = startAndReadCode(EMAIL);
        authService.verifySignup(new VerifySignupRequest(EMAIL, otp));

        assertThatThrownBy(() -> authService.verifySignup(new VerifySignupRequest(EMAIL, otp)))
                .isInstanceOf(InvalidOtpException.class);
    }

    @Test
    void resend_killsThePreviousCode() {
        authService.startSignup(new StartSignupRequest(EMAIL, TURNSTILE, null));
        authService.startSignup(new StartSignupRequest(EMAIL, TURNSTILE, null));
        ArgumentCaptor<String> otps = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000).times(2)).sendVerificationOtp(eq(EMAIL), otps.capture());
        String first = otps.getAllValues().get(0);
        String second = otps.getAllValues().get(1);

        if (!first.equals(second)) {
            assertThatThrownBy(() -> authService.verifySignup(new VerifySignupRequest(EMAIL, first)))
                    .isInstanceOf(InvalidOtpException.class);
        }
        assertThat(authService.verifySignup(new VerifySignupRequest(EMAIL, second)).signupToken()).isNotBlank();
    }

    @Test
    void complete_withTakenUsername_keepsTheTicketUsable() {
        authService.register(new RegisterRequest("taken", "other@example.com", "Password1!", TURNSTILE));
        String ticket = ticketFor(EMAIL);

        assertThatThrownBy(() -> authService.completeSignup(
                new CompleteSignupRequest(ticket, "TAKEN", "Password1!")))
                .isInstanceOf(UsernameAlreadyExistsException.class);

        assertThat(authService.completeSignup(new CompleteSignupRequest(ticket, "fresh", "Password1!"))
                .accessToken()).isNotBlank();
    }

    @Test
    void complete_ticketIsSingleUse() {
        String ticket = ticketFor(EMAIL);
        authService.completeSignup(new CompleteSignupRequest(ticket, "first", "Password1!"));

        assertThatThrownBy(() -> authService.completeSignup(
                new CompleteSignupRequest(ticket, "second", "Password1!")))
                .isInstanceOf(InvalidSignupTokenException.class);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void complete_withUnknownTicket_isRefused() {
        assertThatThrownBy(() -> authService.completeSignup(
                new CompleteSignupRequest("not-a-ticket", "someone", "Password1!")))
                .isInstanceOf(InvalidSignupTokenException.class);
    }
}
