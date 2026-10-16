package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.AddEmailRequest;
import com.tiktok.authservice.dto.request.ForgotPasswordRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
import com.tiktok.authservice.dto.request.StartSignupRequest;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import com.tiktok.authservice.exception.TurnstileVerificationException;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import com.tiktok.crypto.hash.HashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The resend ticket ({@link OtpSendGate}) through the calls that mail a code, against real
 * Postgres and Redis like {@link AuthServiceImplTest}. Turnstile is mocked to pass {@link #TURNSTILE}
 * and refuse anything else, a missing token included, as Cloudflare would; so a send that gets
 * through without that token got through on its ticket.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ResendTicketTest {

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
        doThrow(new TurnstileVerificationException())
                .when(turnstileService).verify(argThat(token -> !TURNSTILE.equals(token)));
    }

    private String startSignup(String email, String turnstileToken, String resendTicket) {
        return authService.startSignup(new StartSignupRequest(email, turnstileToken, resendTicket))
                .resendTicket();
    }

    @Test
    void resend_withTheTicket_mailsAgainWithoutAskingTurnstile() {
        String ticket = startSignup(EMAIL, TURNSTILE, null);
        assertThat(ticket).isNotBlank();

        assertThat(startSignup(EMAIL, null, ticket))
                .as("the client keeps whatever ticket the latest send answered with")
                .isEqualTo(ticket);

        verify(turnstileService, times(1)).verify(any());
        verify(mailService, timeout(2000).times(2)).sendVerificationOtp(eq(EMAIL), anyString());
    }

    @Test
    void ticket_mailsNoOtherAddress() {
        String ticket = startSignup(EMAIL, TURNSTILE, null);

        assertThatThrownBy(() -> startSignup("other@example.com", null, ticket))
                .as("mailing address after address is exactly what Turnstile is there to stop")
                .isInstanceOf(TurnstileVerificationException.class);

        verify(mailService, after(500).never()).sendVerificationOtp(eq("other@example.com"), anyString());
    }

    @Test
    void ticket_coversOnlyThePurposeItWasEarnedFor() {
        String ticket = startSignup(EMAIL, TURNSTILE, null);

        assertThatThrownBy(() -> authService.forgotPassword(new ForgotPasswordRequest(EMAIL, null, ticket)))
                .isInstanceOf(TurnstileVerificationException.class);
    }

    @Test
    void ticket_livesFifteenMinutesFromItsChallenge_andUsingItDoesNotExtendThat() {
        String ticket = startSignup(EMAIL, TURNSTILE, null);
        String key = "auth:otp-resend:" + HashUtils.sha256(ticket);
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(890L, 900L);

        redisTemplate.expire(key, Duration.ofSeconds(30));
        startSignup(EMAIL, null, ticket);

        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
                .as("one solved challenge must not keep mailing an address as the budget refills")
                .isBetween(1L, 30L);
    }

    @Test
    void ticket_stillSpendsTheSendBudget() {
        String ticket = startSignup(EMAIL, TURNSTILE, null);
        startSignup(EMAIL, null, ticket);
        startSignup(EMAIL, null, ticket);

        assertThatThrownBy(() -> startSignup(EMAIL, null, ticket))
                .isInstanceOf(TooManyOtpRequestsException.class);
    }

    @Test
    void unknownTicket_fallsBackOnTurnstile() {
        assertThatThrownBy(() -> startSignup(EMAIL, null, "made-up"))
                .isInstanceOf(TurnstileVerificationException.class);

        assertThat(startSignup(EMAIL, TURNSTILE, "made-up"))
                .as("a solved challenge earns a ticket of its own")
                .isNotBlank()
                .isNotEqualTo("made-up");
    }

    @Test
    void forgotPassword_handsOutATicketWhetherOrNotTheAddressHasAnAccount() {
        authService.register(new RegisterRequest("johndoe", "john@example.com", "Password1!", TURNSTILE));

        String known = authService.forgotPassword(
                new ForgotPasswordRequest("john@example.com", TURNSTILE, null)).resendTicket();
        String unknown = authService.forgotPassword(
                new ForgotPasswordRequest("ghost@example.com", TURNSTILE, null)).resendTicket();

        assertThat(known).isNotBlank();
        assertThat(unknown).isNotBlank();
        assertThat(authService.forgotPassword(
                new ForgotPasswordRequest("ghost@example.com", null, unknown)).resendTicket())
                .isEqualTo(unknown);
    }

    @Test
    void addEmail_ticketCoversTheResendsOfItsCode() {
        UserResponse account = authService.register(
                new RegisterRequest("johndoe", "old@example.com", "Password1!", TURNSTILE));

        String ticket = authService.addEmail(account.id(), new AddEmailRequest(EMAIL, TURNSTILE)).resendTicket();
        authService.resendVerification(new ResendVerificationRequest(EMAIL, null, ticket));

        verify(mailService, timeout(2000).times(2)).sendVerificationOtp(eq(EMAIL), anyString());
        // Once for the registration and once for the address; the resend never asked.
        verify(turnstileService, times(2)).verify(any());
    }
}
