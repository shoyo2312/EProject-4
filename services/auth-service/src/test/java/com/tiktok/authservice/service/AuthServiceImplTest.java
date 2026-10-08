package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.ForgotPasswordRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResetPasswordRequest;
import com.tiktok.authservice.dto.request.VerifyEmailRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.AuthSecurityEvent;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserIdentity;
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
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.crypto.jwt.RevocationKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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

    @MockBean
    private TurnstileService turnstileService;

    @Autowired
    private AuthService authService;

    @Autowired
    private InternalApiToken internalApiToken;

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

    @Autowired
    private AuthSecurityEventRepository securityEventRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @BeforeEach
    void cleanUp() {
        // Before the users it references — auth_security_events carries a foreign key, and these
        // rows are committed by their own transaction rather than rolled back with the test.
        securityEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        userIdentityRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * Puts a request on the thread the way Spring MVC would, so the recorder — which reads the
     * current request rather than taking the address as a parameter — sees one. A client address
     * arrives the way api-gateway forwards it: beside the internal token that vouches for it.
     */
    private MockHttpServletRequest arriveFrom(String clientIp, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (clientIp != null) {
            request.addHeader("X-Client-Ip", clientIp);
            request.addHeader(InternalApiToken.HEADER, internalApiToken.value());
        }
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private RegisterRequest validRegisterRequest() {
        return new RegisterRequest("johndoe", "john@example.com", "password123", "test-turnstile-token");
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

        RegisterRequest duplicate = new RegisterRequest("johndoe", "other@example.com", "password123", "test-turnstile-token");

        assertThatThrownBy(() -> authService.register(duplicate))
                .isInstanceOf(UsernameAlreadyExistsException.class);
    }

    @Test
    @Transactional
    void register_duplicateEmail_throwsConflict() {
        authService.register(validRegisterRequest());

        RegisterRequest duplicate = new RegisterRequest("janedoe", "john@example.com", "password123", "test-turnstile-token");

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

    /**
     * Signup no longer lets a username contain "@", but an account registered before that could
     * still be named after someone else's address — and the lookup tried usernames first, so
     * signing in with that address checked the password against the impostor's account.
     */
    @Test
    @Transactional
    void login_withEmail_isNotShadowedByAUsernameSpelledLikeIt() {
        UserResponse victim = registerVerified();
        authService.register(new RegisterRequest("john@example.com", "impostor@example.com",
                "impostor-pass", "test-turnstile-token"));

        TokenResponse tokens = authService.login(new LoginRequest("john@example.com", "password123"));

        assertThat(jwtProvider.parseAccessToken(tokens.accessToken()).getSubject())
                .isEqualTo(String.valueOf(victim.id()));
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

        authService.logout(initial.refreshToken(), null);

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(initial.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @Transactional
    void logout_withUnknownToken_doesNotThrow() {
        authService.logout("unknown-token", null);
    }

    @Test
    @Transactional
    void logout_withAccessToken_blacklistsItsJti() {
        registerVerified();
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(initial.refreshToken(), initial.accessToken());

        String jti = jwtProvider.extractClaims(initial.accessToken()).get("jti", String.class);
        assertThat(jti).isNotBlank();
        assertThat(redisTemplate.hasKey(RevocationKeys.forJti(jti))).isTrue();
    }

    @Test
    @Transactional
    void logout_withoutAccessToken_doesNotBlacklistAnything() {
        registerVerified();
        TokenResponse initial = authService.login(new LoginRequest("johndoe", "password123"));

        authService.logout(initial.refreshToken(), null);

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

        authService.logout(tokens.refreshToken(), tokens.refreshToken());

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

        markVerified(authService.register(new RegisterRequest("janedoe", "jane@example.com", "password123", "test-turnstile-token")));
        TokenResponse caller = authService.login(new LoginRequest("janedoe", "password123"));

        authService.logout(victim.refreshToken(), caller.accessToken());

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

        authService.logout(tokens.refreshToken(), null);

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

    /** The same burst against a password: five attempts reach bcrypt, the rest are refused. */
    @Test
    void login_parallelBurst_getsNoMoreThanFiveAttempts() throws Exception {
        registerVerified();

        List<Class<?>> outcomes = inParallel(40,
                () -> authService.login(new LoginRequest("johndoe", "wrongpass")));

        assertThat(outcomes).filteredOn(InvalidCredentialsException.class::equals).hasSize(5);
        assertThat(outcomes).filteredOn(TooManyLoginAttemptsException.class::equals).hasSize(35);
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
                new RegisterRequest("user:" + victim.id(), "impostor@example.com", "password123", "test-turnstile-token")));

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
                new RegisterRequest("johndoe", "John@Example.COM", "password123", "test-turnstile-token"));

        assertThat(response.email()).isEqualTo("john@example.com");
    }

    @Test
    @Transactional
    void register_emailDifferingOnlyInCase_throwsConflict() {
        authService.register(validRegisterRequest());

        RegisterRequest duplicate = new RegisterRequest("janedoe", "JOHN@EXAMPLE.COM", "password123", "test-turnstile-token");

        assertThatThrownBy(() -> authService.register(duplicate))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    @Transactional
    void login_withDifferentEmailCase_returnsTokens() {
        markVerified(authService.register(new RegisterRequest("johndoe", "John@Example.com", "password123", "test-turnstile-token")));

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

        authService.logout(phone.refreshToken(), null);

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

    /**
     * The limit used to read the counter and record a failure only after the code had been
     * checked, so every submission in a parallel burst read the same count and got its guess.
     * Taking the guess up front leaves exactly five of them, however many arrive at once.
     */
    @Test
    void verifyEmail_parallelBurst_getsNoMoreThanFiveGuesses() throws Exception {
        authService.register(validRegisterRequest());
        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), org.mockito.ArgumentMatchers.anyString());

        List<Class<?>> outcomes = inParallel(40,
                () -> authService.verifyEmail(new VerifyEmailRequest("john@example.com", "000000")));

        assertThat(outcomes).filteredOn(InvalidOtpException.class::equals).hasSize(5);
        assertThat(outcomes).filteredOn(TooManyOtpRequestsException.class::equals).hasSize(35);
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
        authService.forgotPassword(new ForgotPasswordRequest("ghost@example.com", "test-turnstile-token"));
    }

    @Test
    void resetPassword_withValidOtp_changesPasswordAndRevokesRefreshTokens() {
        registerVerified();
        TokenResponse tokens = authService.login(new LoginRequest("johndoe", "password123"));

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com", "test-turnstile-token"));

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetOtp(eq("john@example.com"), otpCaptor.capture());

        authService.resetPassword(new ResetPasswordRequest("john@example.com", otpCaptor.getValue(), "newpassword123"));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(tokens.refreshToken())))
                .isInstanceOf(InvalidRefreshTokenException.class);

        TokenResponse relogin = authService.login(new LoginRequest("johndoe", "newpassword123"));
        assertThat(relogin.accessToken()).isNotBlank();
    }

    /**
     * The reset code goes to the address, so spending it proves the address. Without marking it,
     * an account whose address was never verified set a password and then got 403
     * EMAIL_NOT_VERIFIED on it: the case for every social account whose provider never vouched
     * for its email.
     */
    @Test
    void resetPassword_onAnUnverifiedAccount_verifiesTheAddress() {
        authService.register(validRegisterRequest());
        verify(mailService, timeout(2000)).sendVerificationOtp(eq("john@example.com"), org.mockito.ArgumentMatchers.anyString());

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com", "test-turnstile-token"));
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetOtp(eq("john@example.com"), otpCaptor.capture());

        authService.resetPassword(new ResetPasswordRequest("john@example.com", otpCaptor.getValue(), "newpassword123"));

        assertThat(userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull("john@example.com").orElseThrow()
                .isEmailVerified()).isTrue();
        assertThat(authService.login(new LoginRequest("johndoe", "newpassword123")).accessToken()).isNotBlank();
    }

    /**
     * Pre-account takeover, social half: a squatter signed up through Facebook with the victim's
     * address. The victim registers, is told the address is taken, and resets the password — which
     * hands them the account. The squatter's Facebook link must not come with it working.
     */
    @Test
    void resetPassword_onAnUnverifiedAccount_flagsTheProviderLinksAlreadyOnIt() {
        UserResponse squatted = authService.register(validRegisterRequest());
        UserIdentity squattersFacebook = userIdentityRepository.save(UserIdentity.builder()
                .userId(squatted.id()).provider(AuthProvider.FACEBOOK).providerUid("squatter-fb").build());

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com", "test-turnstile-token"));
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetOtp(eq("john@example.com"), otpCaptor.capture());
        authService.resetPassword(new ResetPasswordRequest("john@example.com", otpCaptor.getValue(), "newpassword123"));

        assertThat(userIdentityRepository.findById(squattersFacebook.getId()).orElseThrow()
                .isNeedsReconfirmation()).isTrue();
    }

    /** A link made after the address was proven was made against a proven address, and stays. */
    @Test
    void resetPassword_leavesLinksMadeAfterTheAddressWasProven() {
        UserResponse owner = registerVerified();
        UserIdentity ownGoogle = userIdentityRepository.save(UserIdentity.builder()
                .userId(owner.id()).provider(AuthProvider.GOOGLE).providerUid("owner-google").build());

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com", "test-turnstile-token"));
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetOtp(eq("john@example.com"), otpCaptor.capture());
        authService.resetPassword(new ResetPasswordRequest("john@example.com", otpCaptor.getValue(), "newpassword123"));

        assertThat(userIdentityRepository.findById(ownGoogle.getId()).orElseThrow()
                .isNeedsReconfirmation()).isFalse();
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

    /**
     * Two devices of one account, each with its own chain, refreshing in the same instant — a
     * phone and a laptop coming back from sleep. Every refresh stamped last_login_at by saving the
     * user row, which bumped its version, so the slower of the two failed its optimistic lock and
     * answered 409; a client that reads a failed refresh as "signed out" then was. Several rounds,
     * because a single pair only collides when the two loads land before either commit.
     */
    @Test
    void refresh_twoDevicesRefreshingAtOnce_bothKeepTheirSessions() throws Exception {
        registerVerified();
        String phone = authService.login(new LoginRequest("johndoe", "password123")).refreshToken();
        String laptop = authService.login(new LoginRequest("johndoe", "password123")).refreshToken();

        for (int round = 0; round < 10; round++) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier startTogether = new CyclicBarrier(2);
            try {
                List<Future<TokenResponse>> refreshed = pool.invokeAll(List.of(
                        refreshTask(phone, startTogether),
                        refreshTask(laptop, startTogether)));
                phone = refreshed.get(0).get(30, TimeUnit.SECONDS).refreshToken();
                laptop = refreshed.get(1).get(30, TimeUnit.SECONDS).refreshToken();
            } finally {
                pool.shutdownNow();
            }
        }

        assertThat(userRepository.findByUsernameIgnoreCaseAndDeletedAtIsNull("johndoe").orElseThrow()
                .getLastLoginAt())
                .as("still advanced on every refresh, just without contending for the row's version")
                .isNotNull();
    }

    /**
     * Runs {@code action} on {@code count} threads released together, and answers with what each
     * one threw — the exception class, or {@code Void.class} for a call that returned normally.
     */
    private List<Class<?>> inParallel(int count, Runnable action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CyclicBarrier startTogether = new CyclicBarrier(count);
        Callable<Class<?>> task = () -> {
            startTogether.await(30, TimeUnit.SECONDS);
            try {
                action.run();
                return Void.class;
            } catch (RuntimeException e) {
                return e.getClass();
            }
        };
        try {
            List<Class<?>> outcomes = new ArrayList<>();
            for (Future<Class<?>> outcome : pool.invokeAll(Collections.nCopies(count, task))) {
                try {
                    outcomes.add(outcome.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException | TimeoutException e) {
                    throw new AssertionError("a parallel call did not finish", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
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

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com", "test-turnstile-token"));
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

    // The security log below. No @Transactional on any of it: the recorder commits in a
    // transaction of its own, which is the whole point of it, and a test that rolled back would
    // be asserting against rows the production path never writes that way.

    /**
     * Client hints arrive as RFC 8941 quoted strings, so an unstripped value would be stored with
     * its quotes — and the console would show a device called {@code "SM-A525F"}, quotes included.
     */
    @Test
    void signIn_recordsTheDeviceModelTheBrowserDisclosed() {
        registerVerified();
        MockHttpServletRequest request = arriveFrom("203.0.113.7", "Mozilla/5.0 (Linux; Android 10; K) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36");
        request.addHeader("Sec-CH-UA-Model", "\"SM-A525F\"");
        request.addHeader("Sec-CH-UA-Platform-Version", "\"15.0.0\"");

        authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getDeviceModel, AuthSecurityEvent::getDeviceLabel)
                .containsExactly(tuple("SM-A525F", "Chrome on Android 15 · SM-A525F"));
    }

    /** Every desktop Chromium answers the model hint with "" — stored, that is noise. */
    @Test
    void signIn_fromADesktopRecordsNoModelRatherThanAnEmptyOne() {
        registerVerified();
        MockHttpServletRequest request = arriveFrom("203.0.113.7", "Mozilla/5.0 (Macintosh; Intel Mac "
                + "OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36");
        request.addHeader("Sec-CH-UA-Model", "\"\"");

        authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getDeviceModel)
                .containsExactly((String) null);
    }

    @Test
    void signIn_isRecordedWithTheAddressAndDeviceItCameFrom() {
        registerVerified();
        arriveFrom("203.0.113.7", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36");

        authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getEventType, AuthSecurityEvent::getIpAddress,
                        AuthSecurityEvent::getDeviceLabel)
                .containsExactly(tuple(SecurityEventType.LOGIN_SUCCEEDED, "203.0.113.7", "Chrome on macOS"));
    }

    /**
     * The entry that matters most is written on a path that then throws. Recorded in the caller's
     * transaction it would be rolled back by that throw, and the log would hold every successful
     * sign-in and no failed one — which is the opposite of what a security log is for.
     */
    @Test
    void failedSignIn_isRecordedAlthoughTheAttemptThrows() {
        UserResponse registered = registerVerified();
        arriveFrom("203.0.113.9", null);

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpassword")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getEventType, AuthSecurityEvent::getUserId)
                .containsExactly(tuple(SecurityEventType.LOGIN_FAILED, registered.id()));
    }

    /**
     * The hints are the sender's to write. An oversized one used to push the label past its column,
     * the INSERT failed, and the recorder swallowed that — so the failed attempt was never logged.
     */
    @Test
    void failedSignIn_withOversizedClientHints_isStillRecorded() {
        UserResponse registered = registerVerified();
        MockHttpServletRequest request = arriveFrom("203.0.113.9", "Mozilla/5.0 (Linux; Android 15) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36");
        request.addHeader("Sec-CH-UA-Model", "\"" + "X".repeat(500) + "\"");

        assertThatThrownBy(() -> authService.login(new LoginRequest("johndoe", "wrongpassword")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getEventType, AuthSecurityEvent::getUserId)
                .containsExactly(tuple(SecurityEventType.LOGIN_FAILED, registered.id()));
    }

    /**
     * An attempt on a name nobody holds has no account to be filed under, and inventing one keyed
     * on the typed string would turn the log into a way of asking which usernames are real.
     */
    @Test
    void failedSignIn_againstNoAccount_recordsNothing() {
        arriveFrom("203.0.113.9", null);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "password123")))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(securityEventRepository.findAll()).isEmpty();
    }

    /**
     * A client refreshes every fifteen minutes. Recording that would bury every other event in
     * the account's history under entries saying a session the log already has is still alive.
     */
    @Test
    void refresh_isNotRecordedAsASignIn() {
        registerVerified();
        arriveFrom("203.0.113.7", null);
        TokenResponse session = authService.login(new LoginRequest("johndoe", "password123"));

        authService.refresh(new RefreshTokenRequest(session.refreshToken()));

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getEventType)
                .containsExactly(SecurityEventType.LOGIN_SUCCEEDED);
    }

    @Test
    void passwordReset_isRecordedAtBothEnds() {
        registerVerified();
        arriveFrom("203.0.113.7", null);

        authService.forgotPassword(new ForgotPasswordRequest("john@example.com", "test-turnstile-token"));
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService, timeout(2000)).sendPasswordResetOtp(eq("john@example.com"), otpCaptor.capture());
        authService.resetPassword(
                new ResetPasswordRequest("john@example.com", otpCaptor.getValue(), "newpassword123"));

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getEventType)
                .as("asking for a reset and completing one are different facts about an account")
                .containsExactlyInAnyOrder(SecurityEventType.PASSWORD_RESET_REQUESTED,
                        SecurityEventType.PASSWORD_RESET_COMPLETED);
    }

    /**
     * X-Forwarded-For is written by the client on the way in, so believing it would let an
     * attacker choose the address their own break-in is filed under. Only the header api-gateway
     * sets after resolving the hop chain is read; everything else falls back to the peer.
     */
    @Test
    void anAddressTheClientWroteItselfIsNeverBelieved() {
        registerVerified();
        MockHttpServletRequest request = arriveFrom(null, null);
        request.addHeader("X-Forwarded-For", "198.51.100.66");
        request.setRemoteAddr("10.0.0.4");

        authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getIpAddress)
                .containsExactly("10.0.0.4");
    }

    /**
     * The gateway's header, written by something that is not the gateway: anything that reaches
     * this service's port directly can set X-Client-Ip, and only the internal token says it came
     * through api-gateway.
     */
    @Test
    void aClientIpWithoutTheGatewaysTokenIsNotBelieved() {
        registerVerified();
        MockHttpServletRequest request = arriveFrom(null, null);
        request.addHeader("X-Client-Ip", "198.51.100.66");
        request.addHeader(InternalApiToken.HEADER, "a-guess");
        request.setRemoteAddr("10.0.0.4");

        authService.login(new LoginRequest("johndoe", "password123"));

        assertThat(securityEventRepository.findAll())
                .extracting(AuthSecurityEvent::getIpAddress)
                .containsExactly("10.0.0.4");
    }
}
