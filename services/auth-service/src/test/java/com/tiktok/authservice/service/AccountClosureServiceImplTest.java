package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.CloseAccountRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.response.DeletionScheduledResponse;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserIdentity;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.AccountClosureNotAllowedException;
import com.tiktok.authservice.exception.AccountDeactivatedException;
import com.tiktok.authservice.exception.AccountDeletionPendingException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.ReconfirmationFailedException;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountClosureServiceImplTest {

    private static final String PASSWORD = "password123";

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
    private AccountClosureService accountClosureService;

    @Autowired
    private AccountDeletionSweep accountDeletionSweep;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserModerationService userModerationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private AuthSecurityEventRepository securityEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        securityEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void deactivating_closesTheAccount_endsItsSessions_andAnnouncesIt() {
        Long id = signedUpWithPassword();
        authService.login(new LoginRequest("johndoe", PASSWORD));

        accountClosureService.deactivate(id, byPassword(PASSWORD));

        User user = userRepository.findById(id).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        assertThat(user.getDeactivatedAt()).isNotNull();
        assertThat(user.getDeletionScheduledAt()).isNull();
        assertThat(refreshTokenRepository.findAll()).allSatisfy(token -> assertThat(token.isActive()).isFalse());
        assertThat(accountEvents()).singleElement().satisfies(payload ->
                assertThat(payload).contains("\"state\":\"DEACTIVATED\""));
    }

    @Test
    void aWrongPassword_closesNothing() {
        Long id = signedUpWithPassword();

        assertThatThrownBy(() -> accountClosureService.deactivate(id, byPassword("not-it")))
                .isInstanceOf(ReconfirmationFailedException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS")
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);

        assertThat(userRepository.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(accountEvents()).isEmpty();
    }

    /** What happened is told only after the credential checked out. */
    @Test
    void signingInToAClosedAccount_saysSo_onlyWithTheRightPassword() {
        Long id = signedUpWithPassword();
        accountClosureService.deactivate(id, byPassword(PASSWORD));

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "not-it")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", PASSWORD)))
                .isInstanceOf(AccountDeactivatedException.class);
    }

    @Test
    void signingInWithReactivate_reopensTheAccount() {
        Long id = signedUpWithPassword();
        accountClosureService.deactivate(id, byPassword(PASSWORD));

        TokenResponse tokens = authService.login(new LoginRequest("johndoe", PASSWORD, null, null, true));

        assertThat(tokens.accessToken()).isNotBlank();
        User user = userRepository.findById(id).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getDeactivatedAt()).isNull();
        assertThat(accountEvents()).hasSize(2).last().satisfies(payload ->
                assertThat(payload).contains("\"state\":\"ACTIVE\""));
    }

    @Test
    void askingForDeletion_schedulesIt_andReopeningCancelsIt() {
        Long id = signedUpWithPassword();

        DeletionScheduledResponse scheduled = accountClosureService.requestDeletion(id, byPassword(PASSWORD));

        assertThat(scheduled.deletionScheduledAt())
                .isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(Duration.ofMinutes(1)));
        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", PASSWORD)))
                .isInstanceOf(AccountDeletionPendingException.class);

        authService.login(new LoginRequest("johndoe", PASSWORD, null, null, true));

        assertThat(userRepository.findById(id).orElseThrow().getDeletionScheduledAt()).isNull();
    }

    @Test
    void anAdmin_cannotCloseTheirAccount() {
        Long id = signedUpWithPassword();
        User user = userRepository.findById(id).orElseThrow();
        user.promoteToAdmin();
        userRepository.save(user);

        assertThatThrownBy(() -> accountClosureService.deactivate(id, byPassword(PASSWORD)))
                .isInstanceOf(AccountClosureNotAllowedException.class);
    }

    @Test
    void liftingABan_leavesAClosedAccountClosed() {
        Long id = signedUpWithPassword();
        accountClosureService.deactivate(id, byPassword(PASSWORD));

        userModerationService.ban(id, 99L, "spam", null);
        userModerationService.unban(id, 99L);

        assertThat(userRepository.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void anAccountWithNoPassword_reconfirmsWithAMailedCode() {
        Long id = socialOnlyAccount("social@example.com");

        accountClosureService.sendClosureCode(id);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(5000)).sendAccountClosureOtp(eq("social@example.com"), code.capture());

        assertThatThrownBy(() -> accountClosureService.deactivate(id, byOtp("000000".equals(code.getValue()) ? "111111" : "000000")))
                .isInstanceOf(InvalidOtpException.class);
        accountClosureService.deactivate(id, byOtp(code.getValue()));

        assertThat(userRepository.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.DEACTIVATED);
    }

    @Test
    void theSweep_deletesWhatIsDue_evenUnderABan_andFreesTheCredentials() {
        Long id = signedUpWithPassword();
        userIdentityRepository.save(UserIdentity.builder()
                .userId(id).provider(AuthProvider.GOOGLE).providerUid("google-uid").build());
        accountClosureService.requestDeletion(id, byPassword(PASSWORD));
        userModerationService.ban(id, 99L, "spam", null);
        jdbcTemplate.update("update users set deletion_scheduled_at = now() - interval '1 minute' where id = ?", id);

        accountDeletionSweep.deleteDueAccounts();

        User erased = userRepository.findById(id).orElseThrow();
        assertThat(erased.isDeleted()).isTrue();
        assertThat(erased.getEmail()).isNull();
        assertThat(erased.getPasswordHash()).isNull();
        assertThat(userIdentityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, "google-uid")).isEmpty();
        assertThat(accountEvents()).last().satisfies(payload -> assertThat(payload).contains("\"state\":\"DELETED\""));

        // The address and the handle can be signed up with again, as a new account.
        UserResponse again = authService.register(
                new RegisterRequest("johndoe", "john@example.com", PASSWORD, "test-turnstile-token"));
        assertThat(again.id()).isNotEqualTo(id);
    }

    @Test
    void theSweep_leavesAnAccountThatWasNotDueYet() {
        Long id = signedUpWithPassword();
        accountClosureService.requestDeletion(id, byPassword(PASSWORD));

        accountDeletionSweep.deleteDueAccounts();

        assertThat(userRepository.findById(id).orElseThrow().isDeleted()).isFalse();
    }

    private Long signedUpWithPassword() {
        UserResponse registered = authService.register(
                new RegisterRequest("johndoe", "john@example.com", PASSWORD, "test-turnstile-token"));
        User user = userRepository.findById(registered.id()).orElseThrow();
        user.markEmailVerified();
        userRepository.save(user);
        outboxEventRepository.deleteAll();
        return registered.id();
    }

    private Long socialOnlyAccount(String email) {
        User user = User.builder()
                .username("social")
                .email(email)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
        user.markEmailVerified();
        return userRepository.save(user).getId();
    }

    private List<String> accountEvents() {
        return outboxEventRepository.findAll().stream()
                .filter(event -> "ACCOUNT".equals(event.getAggregateType()))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .map(OutboxEvent::getPayload)
                .toList();
    }

    private static CloseAccountRequest byPassword(String password) {
        return new CloseAccountRequest(password, null, null, null);
    }

    private static CloseAccountRequest byOtp(String otp) {
        return new CloseAccountRequest(null, otp, null, null);
    }
}
