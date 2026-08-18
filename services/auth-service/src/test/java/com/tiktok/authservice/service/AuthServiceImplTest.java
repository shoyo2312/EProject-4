package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.ForgotPasswordRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResetPasswordRequest;
import com.tiktok.authservice.dto.request.VerifyEmailRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.EmailAlreadyExistsException;
import com.tiktok.authservice.exception.EmailNotVerifiedException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.TooManyLoginAttemptsException;
import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import com.tiktok.authservice.exception.UserNotFoundException;
import com.tiktok.authservice.exception.UsernameAlreadyExistsException;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.crypto.jwt.RevocationKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

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

    @MockBean
    private MailService mailService;

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
    private JwtProvider jwtProvider;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AccessTokenBlacklist accessTokenBlacklist;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private RegisterRequest validRegisterRequest() {
        return new RegisterRequest("johndoe", "john@example.com", "password123");
    }

    /**
     * Login now requires a confirmed email address, so every test that just needs a usable
     * account flips the flag directly rather than replaying the OTP round trip — which only
     * works in the tests that actually commit, since the mail is sent on an AFTER_COMMIT event.
     */
    private UserResponse registerVerified() {
        return markVerified(authService.register(validRegisterRequest()));
    }

    private UserResponse markVerified(UserResponse registered) {
        User user = userRepository.findById(registered.id()).orElseThrow();
        user.markEmailVerified();
        userRepository.save(user);
        return registered;
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
        registerVerified();

        TokenResponse tokens = authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(jwtProvider.isValid(tokens.accessToken())).isTrue();
    }

    @Test
    @Transactional
    void login_withEmailInsteadOfUsername_returnsTokens() {
        registerVerified();

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
        registerVerified();
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        TokenResponse refreshed = authService.refresh(new RefreshTokenRequest(initial.refreshToken()));

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(jwtProvider.isValid(refreshed.accessToken())).isTrue();
    }

    @Test
    @Transactional
    void refresh_withAccessTokenInsteadOfRefreshToken_throwsInvalidRefreshToken() {
        registerVerified();
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
        registerVerified();
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.refresh(new RefreshTokenRequest(initial.refreshToken()));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(initial.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Transactional
    void logout_revokesRefreshToken() {
        registerVerified();
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
        registerVerified();
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(initial.refreshToken()), initial.accessToken());

        String jti = jwtProvider.extractClaims(initial.accessToken()).get("jti", String.class);
        assertThat(jti).isNotBlank();
        assertThat(redisTemplate.hasKey(RevocationKeys.forJti(jti))).isTrue();
    }

    @Test
    @Transactional
    void logout_withoutAccessToken_doesNotBlacklistAnything() {
        registerVerified();
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(initial.refreshToken()), null);

        String jti = jwtProvider.extractClaims(initial.accessToken()).get("jti", String.class);
        assertThat(redisTemplate.hasKey(RevocationKeys.forJti(jti))).isFalse();
    }

    /**
     * Both token types are signed with the same secret, so a plain isValid() check accepts a
     * refresh token presented as a bearer. The blacklist is an access-token namespace: writing a
     * refresh jti into it burns a key no read side ever consults.
     */
    @Test
    @Transactional
    void logout_withRefreshTokenAsBearer_doesNotBlacklistIt() {
        registerVerified();
        TokenResponse tokens = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(tokens.refreshToken()), tokens.refreshToken());

        String refreshJti = jwtProvider.extractClaims(tokens.refreshToken()).get("jti", String.class);
        assertThat(redisTemplate.hasKey(RevocationKeys.forJti(refreshJti))).isFalse();
    }

    /**
     * A request that ends an account other than the one it authenticated as is not a logout. No
     * escalation either way — holding someone's refresh token already buys sessions, which beats
     * ending one — but the endpoint has no reason to honour the combination, and refusing gives
     * the mismatch somewhere to be logged.
     */
    @Test
    @Transactional
    void logout_withAnotherUsersRefreshToken_leavesThatSessionAlone() {
        registerVerified();
        TokenResponse victim = authService.login(new LoginRequest("johndoe", "password123"));

        markVerified(authService.register(new RegisterRequest("janedoe", "jane@example.com", "password123")));
        TokenResponse caller = authService.login(new LoginRequest("janedoe", "password123"));

        authService.logout(new RefreshTokenRequest(victim.refreshToken()), caller.accessToken());

        assertThat(authService.refresh(new RefreshTokenRequest(victim.refreshToken())).accessToken())
                .as("the victim's session survives a logout it never asked for")
                .isNotBlank();
    }

    /**
     * Logging out with an expired access token — the client noticed it was signed out and is
     * cleaning up — has to keep working, which is why the endpoint stays permitAll and the
     * refresh token is what authorizes the revocation.
     */
    @Test
    @Transactional
    void logout_withoutAnAccessToken_stillRevokesTheRefreshToken() {
        registerVerified();
        TokenResponse tokens = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(tokens.refreshToken()), null);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokens.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
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
        registerVerified();

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpass")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        authService.login(new LoginRequest("johndoe", "password123"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpass")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    /**
     * The limit is per account, not per spelling of it. Keyed on the raw usernameOrEmail, the two
     * ways of naming one account were two Redis counters and simply alternating them bought an
     * attacker 10 attempts for the 5 the limit promises.
     */
    @Test
    @Transactional
    void login_alternatingUsernameAndEmail_sharesOneAttemptBudget() {
        registerVerified();

        for (int i = 0; i < 5; i++) {
            String identifier = i % 2 == 0 ? "johndoe" : "john@example.com";
            assertThatThrownBy(() -> authService.login(new LoginRequest(identifier, "wrongpass")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        // Both spellings are now spent, whichever one the sixth attempt uses.
        assertThatThrownBy(() -> authService.login(new LoginRequest("john@example.com", "password123")))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "password123")))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    /**
     * An account nobody registered still has to be throttled. Leaving the unknown branch
     * unlimited would turn "this identifier never gets blocked" into a free existence check.
     */
    @Test
    @Transactional
    void login_unknownAccount_isThrottledLikeARealOne() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "wrongpass")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "wrongpass")))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    /**
     * Usernames are only length-checked, so one can be spelled exactly like the key of another
     * account. Without the namespace prefixes, registering {@code user:<id>} would let its owner
     * burn a stranger's attempt budget — or clear it, by logging in successfully.
     */
    @Test
    @Transactional
    void login_usernameShapedLikeAnotherAccountsKey_doesNotShareItsBudget() {
        UserResponse victim = registerVerified();
        markVerified(authService.register(
                new RegisterRequest("user:" + victim.id(), "impostor@example.com", "password123")));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("user:" + victim.id(), "wrongpass")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        // The impostor is locked out; the victim they were named after is untouched.
        assertThatThrownBy(() -> authService.login(new LoginRequest("user:" + victim.id(), "wrongpass")))
                .isInstanceOf(TooManyLoginAttemptsException.class);
        assertThat(authService.login(new LoginRequest("johndoe", "password123")).accessToken()).isNotBlank();
    }

    /**
     * The refresh token is signed with the same secret as the access token, so every filter that
     * authenticates a bearer token has to reject it explicitly — otherwise a stolen refresh token
     * authenticates requests for its full 7-day life.
     */
    @Test
    @Transactional
    void issuedTokens_onlyTheAccessTokenPassesTheBearerCheck() {
        registerVerified();
        TokenResponse tokens = authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(jwtProvider.isValidAccessToken(tokens.accessToken())).isTrue();
        assertThat(jwtProvider.isValidAccessToken(tokens.refreshToken())).isFalse();
        assertThat(jwtProvider.isValid(tokens.refreshToken()))
                .as("refresh token is still a structurally valid JWT — only the tokenType claim separates them")
                .isTrue();
    }

    @Test
    @Transactional
    void register_lowercasesEmail() {
        UserResponse response = authService.register(
                new RegisterRequest("johndoe", "John@Example.COM", "password123"));

        assertThat(response.email()).isEqualTo("john@example.com");
    }

    @Test
    @Transactional
    void register_emailDifferingOnlyInCase_throwsConflict() {
        authService.register(validRegisterRequest());

        RegisterRequest duplicate = new RegisterRequest("janedoe", "JOHN@EXAMPLE.COM", "password123");

        assertThatThrownBy(() -> authService.register(duplicate))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    @Transactional
    void login_withDifferentEmailCase_returnsTokens() {
        markVerified(authService.register(new RegisterRequest("johndoe", "John@Example.com", "password123")));

        TokenResponse tokens = authService.login(new LoginRequest("john@example.com", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
    }

    @Test
    @Transactional
    void login_withDifferentUsernameCase_returnsTokens() {
        registerVerified();

        TokenResponse tokens = authService.login(new LoginRequest("JohnDoe", "password123"));

        assertThat(tokens.accessToken()).isNotBlank();
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

    /**
     * The whole verify-email flow was decoration until login actually consulted the flag: anyone
     * could register with an address they do not own and use the account immediately.
     */
    @Test
    @Transactional
    void login_withUnverifiedEmail_throwsEmailNotVerified() {
        authService.register(validRegisterRequest());

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "password123")))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    /**
     * Distinct from a wrong password on purpose — the client has to know to offer "resend code"
     * rather than "try again", and the caller already proved it knows the password.
     */
    @Test
    @Transactional
    void login_withUnverifiedEmailAndWrongPassword_stillReportsInvalidCredentials() {
        authService.register(validRegisterRequest());

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpass")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    /** A rejected-but-correct password is not a guess, so it must not burn the attempt budget. */
    @Test
    @Transactional
    void login_withUnverifiedEmail_doesNotCountAgainstTheAttemptBudget() {
        UserResponse registered = authService.register(validRegisterRequest());

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "password123")))
                    .isInstanceOf(EmailNotVerifiedException.class);
        }

        markVerified(registered);
        assertThat(authService.login(new LoginRequest("johndoe", "password123")).accessToken()).isNotBlank();
    }

    /**
     * Replaying a logged-out token is a stale client, not a theft: the token has no successor, so
     * nobody else can be holding the chain. Kicking the user's other devices here would make
     * logging out of one device log you out of all of them.
     */
    @Test
    @Transactional
    void refresh_replayingALoggedOutToken_leavesOtherSessionsAlone() {
        registerVerified();
        TokenResponse phone = authService.login(new LoginRequest("johndoe", "password123"));
        TokenResponse laptop = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(new RefreshTokenRequest(phone.refreshToken()), null);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(phone.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(authService.refresh(new RefreshTokenRequest(laptop.refreshToken())).accessToken())
                .as("logging out one device must not end the others")
                .isNotBlank();
    }

    // No @Transactional below: the mail send is wired via an AFTER_COMMIT event listener,
    // so the transaction needs to actually commit for MailService to be invoked.

    @Test
    void register_sendsVerificationOtpAfterCommit() {
        authService.register(validRegisterRequest());

        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void verifyEmail_withValidOtp_marksUserVerified() {
        UserResponse registered = authService.register(validRegisterRequest());

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), otpCaptor.capture());

        authService.verifyEmail(new VerifyEmailRequest("john@example.com", otpCaptor.getValue()));

        User user = userRepository.findById(registered.id()).orElseThrow();
        assertThat(user.isEmailVerified()).isTrue();
    }

    /**
     * The code is spent by a single UPDATE guarded on {@code used_at IS NULL}, so a second
     * submission matches no row — a read-then-write would let two concurrent submissions of one
     * code both succeed. See VerificationTokenRepository.claimForUse.
     */
    @Test
    void verifyEmail_withAlreadySpentOtp_throwsInvalidOtp() {
        authService.register(validRegisterRequest());

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), otpCaptor.capture());

        authService.verifyEmail(new VerifyEmailRequest("john@example.com", otpCaptor.getValue()));

        assertThatThrownBy(() -> authService.verifyEmail(
                new VerifyEmailRequest("john@example.com", otpCaptor.getValue())))
                .isInstanceOf(InvalidOtpException.class);
    }

    /** Expiry is part of the same UPDATE's predicate, and is aged here rather than waited out. */
    @Test
    void verifyEmail_withExpiredOtp_throwsInvalidOtp() {
        UserResponse registered = authService.register(validRegisterRequest());

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), otpCaptor.capture());

        jdbcTemplate.update("update verification_tokens set expires_at = now() - interval '1 hour'");

        assertThatThrownBy(() -> authService.verifyEmail(
                new VerifyEmailRequest("john@example.com", otpCaptor.getValue())))
                .isInstanceOf(InvalidOtpException.class);
        assertThat(userRepository.findById(registered.id()).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void verifyEmail_withWrongOtp_throwsInvalidOtp() {
        authService.register(validRegisterRequest());

        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), org.mockito.ArgumentMatchers.anyString());

        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest("john@example.com", "000000")))
                .isInstanceOf(InvalidOtpException.class);
    }

    /**
     * A 6-digit OTP is only 1e6 values; without a guess cap an attacker spread over many IPs
     * walks the whole space inside the code's 15-minute life.
     */
    @Test
    void verifyEmail_afterFiveWrongOtps_blocksFurtherGuesses() {
        authService.register(validRegisterRequest());

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), otpCaptor.capture());

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest("john@example.com", "000000")))
                    .isInstanceOf(InvalidOtpException.class);
        }

        // Even the correct code is refused once the guess budget is spent.
        assertThatThrownBy(() -> authService.verifyEmail(
                new VerifyEmailRequest("john@example.com", otpCaptor.getValue())))
                .isInstanceOf(TooManyOtpRequestsException.class);
    }

    @Test
    void verifyEmail_withUnknownEmail_countsAgainstTheGuessBudget() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest("ghost@example.com", "000000")))
                    .isInstanceOf(InvalidOtpException.class);
        }

        assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest("ghost@example.com", "000000")))
                .isInstanceOf(TooManyOtpRequestsException.class);
    }

    @Test
    void verifyEmail_successResetsTheGuessCounter() {
        authService.register(validRegisterRequest());

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), otpCaptor.capture());

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.verifyEmail(new VerifyEmailRequest("john@example.com", "000000")))
                    .isInstanceOf(InvalidOtpException.class);
        }

        authService.verifyEmail(new VerifyEmailRequest("john@example.com", otpCaptor.getValue()));

        assertThat(redisTemplate.hasKey("auth:otp-fail:email-verification:john@example.com")).isFalse();
    }

    @Test
    void forgotPassword_withUnknownEmail_doesNotThrow() {
        authService.forgotPassword(new ForgotPasswordRequest("ghost@example.com"));
    }

    @Test
    void resetPassword_withValidOtp_changesPasswordAndRevokesRefreshTokens() {
        registerVerified();
        TokenResponse tokens = authService.login(new LoginRequest("johndoe", "password123"));

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com"));

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetOtp(eq("john@example.com"), otpCaptor.capture());

        authService.resetPassword(new ResetPasswordRequest("john@example.com", otpCaptor.getValue(), "newpassword123"));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokens.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);

        TokenResponse relogin = authService.login(new LoginRequest("johndoe", "newpassword123"));
        assertThat(relogin.accessToken()).isNotBlank();
    }

    /**
     * Rotation without replay detection is worse than no rotation: whoever refreshes first keeps
     * a valid chain forever, and the loser just sees "please sign in again" — no alarm anywhere.
     * A rotated token coming back means two parties hold it, so every session for that user dies
     * and both sides have to prove who they are again.
     *
     * <p>Not @Transactional: the revocation runs in its own transaction (it has to survive the
     * rejection that follows), which would deadlock against uncommitted rows held by the test.
     */
    @Test
    void refresh_replayingARotatedToken_endsEverySessionForThatUser() {
        registerVerified();
        TokenResponse stolen = authService.login(new LoginRequest("johndoe", "password123"));
        TokenResponse otherDevice = authService.login(new LoginRequest("johndoe", "password123"));

        // The thief gets there first and rotates the chain.
        TokenResponse thiefsChain = authService.refresh(new RefreshTokenRequest(stolen.refreshToken()));

        // The real user's client does not refresh until its access token runs out, a quarter of
        // an hour later — far outside the window in which a lost-response retry is plausible.
        backdateRotation(stolen.refreshToken(), Duration.ofMinutes(15));

        // The real user then presents the token they still hold — that is the tell.
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(stolen.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(thiefsChain.refreshToken())))
                .as("the chain the thief walked away with must be dead too")
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(otherDevice.refreshToken())))
                .as("every session for the user ends, not just the replayed chain")
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(accessTokenBlacklist.isBlacklisted(jwtProvider.extractClaims(thiefsChain.accessToken())))
                .as("access tokens are stateless — without the user-wide cutoff the thief keeps ~15 minutes")
                .isTrue();
    }

    /**
     * The race the detection above was blind to. Reading the row, checking rotatedAt == null and
     * then writing it is check-then-act: both requests read null, both rotate, and two live
     * chains walk away — the attacker-and-victim situation itself, waved through by the code
     * meant to catch it. Only the atomic claim decides a winner.
     *
     * <p>Real threads against real Postgres rather than a stubbed repository, because the
     * guarantee under test is the row lock: the loser's UPDATE has to block until the winner
     * commits and then match nothing. Stubbing the ordering would only prove the assertion.
     */
    @Test
    void refresh_twoConcurrentRefreshesOfOneToken_issueExactlyOneChain() throws Exception {
        registerVerified();
        TokenResponse session = authService.login(new LoginRequest("johndoe", "password123"));
        TokenResponse otherDevice = authService.login(new LoginRequest("johndoe", "password123"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        int issued = 0;
        try {
            for (Future<TokenResponse> attempt : pool.invokeAll(List.of(
                    refreshTask(session.refreshToken(), startTogether),
                    refreshTask(session.refreshToken(), startTogether)))) {
                try {
                    assertThat(attempt.get(30, TimeUnit.SECONDS).accessToken()).isNotBlank();
                    issued++;
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(InvalidRefreshTokenException.class);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(issued)
                .as("both requests rotating the same token is two live chains from one login")
                .isEqualTo(1);
        assertThat(refreshTokenRepository.findAll().stream().filter(RefreshToken::isActive))
                .as("the successor and the untouched device — not two successors")
                .hasSize(2);
    }

    /**
     * The other half of the same race, and the reason it is not simply "0 rows means theft": a
     * client that retries a refresh whose response it never received presents exactly what a
     * thief presents. Within the grace window that retry is rejected — it will ask again and get
     * a chain — but it must not take the user's other devices down with it.
     */
    @Test
    void refresh_retryingWithinTheGraceWindow_leavesOtherSessionsAlone() {
        registerVerified();
        TokenResponse session = authService.login(new LoginRequest("johndoe", "password123"));
        TokenResponse otherDevice = authService.login(new LoginRequest("johndoe", "password123"));

        TokenResponse rotated = authService.refresh(new RefreshTokenRequest(session.refreshToken()));

        // No backdating: the replay lands within milliseconds, which is a retry, not a theft.
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(session.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(authService.refresh(new RefreshTokenRequest(rotated.refreshToken())).accessToken())
                .as("the chain that did win the race stays usable")
                .isNotBlank();
        assertThat(authService.refresh(new RefreshTokenRequest(otherDevice.refreshToken())).accessToken())
                .as("a lost response on one device must not sign the user out everywhere")
                .isNotBlank();
    }

    private Callable<TokenResponse> refreshTask(String refreshToken, CyclicBarrier startTogether) {
        return () -> {
            startTogether.await(30, TimeUnit.SECONDS);
            return authService.refresh(new RefreshTokenRequest(refreshToken));
        };
    }

    /**
     * Ages a token's rotation timestamp, so replaying it reads as arriving long after the
     * successor was handed out rather than as an in-flight retry. Deterministic, unlike sleeping
     * out the grace window, and it leaves the window itself at its production value.
     */
    private void backdateRotation(String refreshToken, Duration by) {
        int updated = jdbcTemplate.update(
                "UPDATE refresh_tokens SET rotated_at = rotated_at - CAST(? AS interval) WHERE token_hash = ?",
                by.toSeconds() + " seconds", HashUtils.sha256(refreshToken));

        assertThat(updated).as("nothing was rotated, so the test is not testing what it claims").isEqualTo(1);
    }

    /**
     * Password reset used to revoke refresh tokens only, leaving the attacker's access token alive
     * until it expired — the exact window the reset is meant to close.
     */
    @Test
    void resetPassword_revokesAccessTokensAlreadyIssued() {
        registerVerified();
        TokenResponse attacker = authService.login(new LoginRequest("johndoe", "password123"));

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com"));
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetOtp(eq("john@example.com"), otpCaptor.capture());

        authService.resetPassword(new ResetPasswordRequest("john@example.com", otpCaptor.getValue(), "newpassword123"));

        assertThat(accessTokenBlacklist.isBlacklisted(jwtProvider.extractClaims(attacker.accessToken())))
                .isTrue();

        // ...while the session the real owner opens right after must work.
        TokenResponse owner = authService.login(new LoginRequest("johndoe", "newpassword123"));
        assertThat(accessTokenBlacklist.isBlacklisted(jwtProvider.extractClaims(owner.accessToken())))
                .as("the cutoff must not lock the user out of the session they just opened")
                .isFalse();
    }
}
