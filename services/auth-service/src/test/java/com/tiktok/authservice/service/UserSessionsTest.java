package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.response.SessionResponse;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.DeviceType;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.SessionEndReason;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserSession;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.SessionNotFoundException;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.UserSessionRepository;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.crypto.jwt.RevocationKeys;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signed-in devices: one laptop/PC and one phone/tablet per account, listed for the owner and
 * signed out remotely. Against Postgres and Redis, through the same beans the endpoints use.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserSessionsTest {

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

    @Autowired
    private TokenIssuer tokenIssuer;

    @Autowired
    private AuthService authService;

    @Autowired
    private SessionRegistry sessionRegistry;

    @Autowired
    private SessionRevoker sessionRevoker;

    @Autowired
    private AccessTokenBlacklist accessTokenBlacklist;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private InternalApiToken internalApiToken;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserSessionRepository sessionRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuthSecurityEventRepository securityEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String CHROME_ON_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final String SAFARI_ON_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";
    private static final String APP_ON_ANDROID = "NowaApp/1.0.0 (Android; Mobile)";
    private static final String APP_ON_IPAD = "NowaApp/1.0.0 (iPad; iPadOS)";

    @BeforeEach
    void cleanUp() {
        jdbc.update("delete from known_devices");
        jdbc.update("delete from known_device_accounts");
        jdbc.update("delete from user_sessions");
        securityEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void arrive(String userAgent, String model) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Ip", "81.2.69.160");
        request.addHeader(InternalApiToken.HEADER, internalApiToken.value());
        request.addHeader("User-Agent", userAgent);
        if (model != null) {
            request.addHeader("Sec-CH-UA-Model", "\"" + model + "\"");
            request.addHeader("Sec-CH-UA-Platform-Version", "\"14\"");
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private User saveUser(UserRole role) {
        return userRepository.save(User.builder()
                .username("johndoe")
                .email("john@example.com")
                .passwordHash("irrelevant")
                .role(role)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private TokenResponse signIn(User user, String userAgent, String model) {
        arrive(userAgent, model);
        return tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
    }

    private Long sessionOf(TokenResponse tokens) {
        return Long.valueOf(RevocationKeys.sessionIdOf(jwtProvider.extractClaims(tokens.accessToken())));
    }

    private boolean accessRevoked(TokenResponse tokens) {
        Claims claims = jwtProvider.extractClaims(tokens.accessToken());
        return accessTokenBlacklist.isBlacklisted(claims);
    }

    private TokenResponse refresh(TokenResponse tokens) {
        return authService.refresh(new RefreshTokenRequest(tokens.refreshToken()));
    }

    @Test
    void aSecondLaptopSignsTheFirstOut_andThePhoneStays() {
        User user = saveUser(UserRole.USER);
        TokenResponse windows = signIn(user, CHROME_ON_WINDOWS, null);
        TokenResponse phone = signIn(user, APP_ON_ANDROID, "SM-A525F");
        TokenResponse mac = signIn(user, SAFARI_ON_MAC, null);

        assertThat(accessRevoked(windows)).isTrue();
        assertThatThrownBy(() -> refresh(windows)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(sessionRepository.findById(sessionOf(windows)).orElseThrow().getRevokeReason())
                .isEqualTo(SessionEndReason.REPLACED);

        assertThat(accessRevoked(phone)).isFalse();
        assertThat(accessRevoked(mac)).isFalse();

        assertThat(sessionRegistry.list(user.getId(), sessionOf(mac)))
                .extracting(SessionResponse::deviceType, SessionResponse::deviceLabel, SessionResponse::current)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(DeviceType.DESKTOP, "Safari on macOS", true),
                        org.assertj.core.groups.Tuple.tuple(DeviceType.MOBILE, "Nowa app on Android 14 · SM-A525F", false));
    }

    @Test
    void aTabletTakesThePhonesSlot() {
        User user = saveUser(UserRole.USER);
        TokenResponse phone = signIn(user, APP_ON_ANDROID, "SM-A525F");
        signIn(user, APP_ON_IPAD, "iPad13,4");

        assertThat(accessRevoked(phone)).isTrue();
        assertThat(sessionRegistry.list(user.getId(), null))
                .singleElement().extracting(SessionResponse::deviceModel).isEqualTo("iPad13,4");
    }

    @Test
    void aRefreshKeepsTheSession_andItsPlace() {
        User user = saveUser(UserRole.USER);
        TokenResponse first = signIn(user, APP_ON_ANDROID, "SM-A525F");
        TokenResponse renewed = refresh(first);

        assertThat(sessionOf(renewed)).isEqualTo(sessionOf(first));
        assertThat(sessionRegistry.list(user.getId(), sessionOf(renewed))).singleElement().satisfies(s -> {
            assertThat(s.location()).isEqualTo("London, England, United Kingdom");
            assertThat(s.ipAddress()).isEqualTo("81.2.69.160");
            assertThat(s.current()).isTrue();
        });
    }

    @Test
    void signingADeviceOutRemotely_killsItsRefreshAndItsAccessToken() {
        User user = saveUser(UserRole.USER);
        TokenResponse laptop = signIn(user, CHROME_ON_WINDOWS, null);
        TokenResponse phone = signIn(user, APP_ON_ANDROID, "SM-A525F");

        sessionRegistry.signOut(user.getId(), sessionOf(phone));

        assertThat(accessRevoked(phone)).isTrue();
        assertThatThrownBy(() -> refresh(phone)).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(accessRevoked(laptop)).isFalse();
        assertThat(sessionRegistry.list(user.getId(), null)).singleElement()
                .extracting(SessionResponse::deviceType).isEqualTo(DeviceType.DESKTOP);
    }

    @Test
    void anotherAccountsSessionCannotBeSignedOut() {
        User user = saveUser(UserRole.USER);
        TokenResponse phone = signIn(user, APP_ON_ANDROID, null);

        assertThatThrownBy(() -> sessionRegistry.signOut(user.getId() + 1, sessionOf(phone)))
                .isInstanceOf(SessionNotFoundException.class);
        assertThat(accessRevoked(phone)).isFalse();
    }

    @Test
    void signOutOthers_keepsOnlyThisDevice() {
        User user = saveUser(UserRole.USER);
        TokenResponse laptop = signIn(user, CHROME_ON_WINDOWS, null);
        TokenResponse phone = signIn(user, APP_ON_ANDROID, null);

        assertThat(sessionRegistry.signOutOthers(user.getId(), sessionOf(phone))).isEqualTo(1);

        assertThat(accessRevoked(laptop)).isTrue();
        assertThat(accessRevoked(phone)).isFalse();
    }

    @Test
    void loggingOut_endsTheSession() {
        User user = saveUser(UserRole.USER);
        TokenResponse phone = signIn(user, APP_ON_ANDROID, null);

        authService.logout(phone.refreshToken(), phone.accessToken());

        assertThat(sessionRepository.findById(sessionOf(phone)).orElseThrow().getRevokeReason())
                .isEqualTo(SessionEndReason.LOGOUT);
        assertThat(sessionRegistry.list(user.getId(), null)).isEmpty();
    }

    @Test
    void revokingEverything_endsEverySession() {
        User user = saveUser(UserRole.USER);
        signIn(user, CHROME_ON_WINDOWS, null);
        signIn(user, APP_ON_ANDROID, null);

        sessionRevoker.revokeAllSessions(user.getId());

        assertThat(sessionRegistry.list(user.getId(), null)).isEmpty();
    }

    @Test
    void anAdminIsNotLimited() {
        User admin = saveUser(UserRole.ADMIN);
        TokenResponse first = signIn(admin, CHROME_ON_WINDOWS, null);
        signIn(admin, CHROME_ON_WINDOWS, null);

        assertThat(accessRevoked(first)).isFalse();
        assertThat(sessionRegistry.list(admin.getId(), null)).hasSize(2);
    }

    /** A token from before sessions existed gets one at its next refresh, limit included. */
    @Test
    void aTokenFromBeforeSessionsIsAdoptedAtRefresh() {
        User user = saveUser(UserRole.USER);
        TokenResponse phone = signIn(user, APP_ON_ANDROID, null);
        String legacy = jwtProvider.generateToken(String.valueOf(user.getId()),
                java.util.Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_REFRESH, "jti", "legacy-jti"),
                60_000);
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(HashUtils.sha256(legacy))
                .expiresAt(Instant.now().plusSeconds(60))
                .build());

        arrive(APP_ON_ANDROID, null);
        TokenResponse adopted = authService.refresh(new RefreshTokenRequest(legacy));

        UserSession session = sessionRepository.findById(sessionOf(adopted)).orElseThrow();
        assertThat(session.getDeviceType()).isEqualTo(DeviceType.MOBILE);
        assertThat(accessRevoked(phone)).isTrue();
        assertThat(sessionRegistry.list(user.getId(), null)).extracting(SessionResponse::id)
                .containsExactly(session.getId());
    }
}
