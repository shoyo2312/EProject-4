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
    private LoginRateLimiter loginRateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AccessTokenBlacklist accessTokenBlacklist;

    @BeforeEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        userRepository.deleteAll();
        loginRateLimiter.reset();
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
