package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.QrLoginCreatedResponse;
import com.tiktok.authservice.dto.response.QrLoginScanResponse;
import com.tiktok.authservice.dto.response.QrLoginStatus;
import com.tiktok.authservice.dto.response.QrLoginStatusResponse;
import com.tiktok.authservice.entity.DeviceType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserSession;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.QrLoginAlreadyScannedException;
import com.tiktok.authservice.exception.QrLoginExpiredException;
import com.tiktok.authservice.exception.QrLoginNotAllowedException;
import com.tiktok.authservice.exception.TooManyQrLoginRequestsException;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.UserSessionRepository;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QR sign-in end to end against Postgres and Redis: the browser creates and polls, the phone scans
 * and decides, and the session that comes out belongs to the browser.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class QrLoginTest {

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
    private QrLoginService qrLoginService;

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
    private static final String APP_ON_ANDROID = "NowaApp/1.0.0 (Android; Mobile)";

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

    @Test
    void approvedCode_signsTheBrowserIn_once_asTheBrowser() {
        User john = saveUser("johndoe", UserRole.USER);

        asBrowser();
        QrLoginCreatedResponse code = qrLoginService.create();
        assertThat(code.qrContent()).isEqualTo("nowa://qr-login?id=" + code.qrId());
        assertThat(code.qrContent()).doesNotContain(code.pollToken());
        assertThat(qrLoginService.poll(code.qrId(), code.pollToken()).status()).isEqualTo(QrLoginStatus.PENDING);

        asPhone();
        QrLoginScanResponse scan = qrLoginService.scan(code.qrId(), john.getId());
        // The confirm screen names the browser that asked, not the phone in hand.
        assertThat(scan.deviceLabel()).isEqualTo("Chrome on Windows");
        assertThat(scan.expiresAt()).isAfter(Instant.now());

        asBrowser();
        QrLoginStatusResponse scanned = qrLoginService.poll(code.qrId(), code.pollToken());
        assertThat(scanned.status()).isEqualTo(QrLoginStatus.SCANNED);
        assertThat(scanned.username()).isEqualTo("johndoe");
        assertThat(scanned.tokens()).isNull();

        asPhone();
        qrLoginService.approve(code.qrId(), john.getId());

        asBrowser();
        QrLoginStatusResponse approved = qrLoginService.poll(code.qrId(), code.pollToken());
        assertThat(approved.status()).isEqualTo(QrLoginStatus.APPROVED);
        assertThat(approved.tokens()).isNotNull();
        assertThat(jwtProvider.extractClaims(approved.tokens().accessToken()).getSubject())
                .isEqualTo(String.valueOf(john.getId()));

        // Recorded as the browser: a desktop session, so the phone's own session is untouched.
        List<UserSession> sessions = sessionRepository.findActiveByUserId(john.getId(), Instant.now());
        assertThat(sessions).extracting(UserSession::getDeviceType).containsExactly(DeviceType.DESKTOP);

        // Handed out once.
        assertThat(qrLoginService.poll(code.qrId(), code.pollToken()).status()).isEqualTo(QrLoginStatus.EXPIRED);
    }

    @Test
    void wrongPollToken_readsAsExpired_andDoesNotSpendTheCode() {
        User john = saveUser("johndoe", UserRole.USER);
        asBrowser();
        QrLoginCreatedResponse code = qrLoginService.create();
        qrLoginService.scan(code.qrId(), john.getId());
        qrLoginService.approve(code.qrId(), john.getId());

        QrLoginStatusResponse stranger = qrLoginService.poll(code.qrId(), "not-the-poll-token");
        assertThat(stranger.status()).isEqualTo(QrLoginStatus.EXPIRED);
        assertThat(stranger.tokens()).isNull();

        assertThat(qrLoginService.poll(code.qrId(), code.pollToken()).status()).isEqualTo(QrLoginStatus.APPROVED);
    }

    @Test
    void secondAccount_cannotScanAClaimedCode_butTheSameAccountMayRetry() {
        User john = saveUser("johndoe", UserRole.USER);
        User jane = saveUser("janedoe", UserRole.USER);
        asBrowser();
        QrLoginCreatedResponse code = qrLoginService.create();

        qrLoginService.scan(code.qrId(), john.getId());
        qrLoginService.scan(code.qrId(), john.getId());

        assertThatThrownBy(() -> qrLoginService.scan(code.qrId(), jane.getId()))
                .isInstanceOf(QrLoginAlreadyScannedException.class);
        // Nor can she approve what he scanned.
        assertThatThrownBy(() -> qrLoginService.approve(code.qrId(), jane.getId()))
                .isInstanceOf(QrLoginExpiredException.class);
    }

    @Test
    void approve_withoutAScan_isRefused() {
        User john = saveUser("johndoe", UserRole.USER);
        asBrowser();
        QrLoginCreatedResponse code = qrLoginService.create();

        assertThatThrownBy(() -> qrLoginService.approve(code.qrId(), john.getId()))
                .isInstanceOf(QrLoginExpiredException.class);
        assertThat(qrLoginService.poll(code.qrId(), code.pollToken()).status()).isEqualTo(QrLoginStatus.PENDING);
    }

    @Test
    void rejectedCode_tellsTheBrowserOnce_andCannotBeApprovedAfter() {
        User john = saveUser("johndoe", UserRole.USER);
        asBrowser();
        QrLoginCreatedResponse code = qrLoginService.create();
        qrLoginService.scan(code.qrId(), john.getId());
        qrLoginService.reject(code.qrId(), john.getId());

        assertThatThrownBy(() -> qrLoginService.approve(code.qrId(), john.getId()))
                .isInstanceOf(QrLoginExpiredException.class);
        assertThat(qrLoginService.poll(code.qrId(), code.pollToken()).status()).isEqualTo(QrLoginStatus.REJECTED);
        assertThat(qrLoginService.poll(code.qrId(), code.pollToken()).status()).isEqualTo(QrLoginStatus.EXPIRED);
        assertThat(sessionRepository.findActiveByUserId(john.getId(), Instant.now())).isEmpty();
    }

    @Test
    void adminAccount_cannotScan() {
        User admin = saveUser("admin", UserRole.ADMIN);
        asBrowser();
        QrLoginCreatedResponse code = qrLoginService.create();

        assertThatThrownBy(() -> qrLoginService.scan(code.qrId(), admin.getId()))
                .isInstanceOf(QrLoginNotAllowedException.class);
    }

    @Test
    void accountBannedAfterApproving_getsNoSession() {
        User john = saveUser("johndoe", UserRole.USER);
        asBrowser();
        QrLoginCreatedResponse code = qrLoginService.create();
        qrLoginService.scan(code.qrId(), john.getId());
        qrLoginService.approve(code.qrId(), john.getId());

        User banned = userRepository.findById(john.getId()).orElseThrow();
        banned.ban("spam", null);
        userRepository.save(banned);

        QrLoginStatusResponse answer = qrLoginService.poll(code.qrId(), code.pollToken());
        assertThat(answer.status()).isEqualTo(QrLoginStatus.REJECTED);
        assertThat(answer.tokens()).isNull();
        assertThat(sessionRepository.findActiveByUserId(john.getId(), Instant.now())).isEmpty();
    }

    @Test
    void unknownOrMalformedCode_isExpired() {
        User john = saveUser("johndoe", UserRole.USER);
        asBrowser();

        assertThat(qrLoginService.poll("AAAAAAAAAAAAAAAAAAAAAA", "x").status()).isEqualTo(QrLoginStatus.EXPIRED);
        assertThat(qrLoginService.poll("../../etc", "x").status()).isEqualTo(QrLoginStatus.EXPIRED);
        assertThatThrownBy(() -> qrLoginService.scan("AAAAAAAAAAAAAAAAAAAAAA", john.getId()))
                .isInstanceOf(QrLoginExpiredException.class);
        assertThatThrownBy(() -> qrLoginService.scan("not a code", john.getId()))
                .isInstanceOf(QrLoginExpiredException.class);
    }

    @Test
    void oneAddress_isLimitedInHowManyCodesItAsksFor() {
        asBrowser();
        for (int i = 0; i < QrLoginServiceImpl.CREATE_LIMIT; i++) {
            qrLoginService.create();
        }
        assertThatThrownBy(() -> qrLoginService.create()).isInstanceOf(TooManyQrLoginRequestsException.class);
    }

    private void asBrowser() {
        arrive(CHROME_ON_WINDOWS, "81.2.69.160");
    }

    private void asPhone() {
        arrive(APP_ON_ANDROID, "81.2.69.161");
    }

    private void arrive(String userAgent, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Ip", address);
        request.addHeader(InternalApiToken.HEADER, internalApiToken.value());
        request.addHeader("User-Agent", userAgent);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private User saveUser(String username, UserRole role) {
        return userRepository.save(User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash("irrelevant")
                .emailVerified(true)
                .role(role)
                .status(UserStatus.ACTIVE)
                .build());
    }
}
