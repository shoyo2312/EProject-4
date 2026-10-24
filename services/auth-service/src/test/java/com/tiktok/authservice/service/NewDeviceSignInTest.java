package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.AuthSecurityEvent;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.common.http.InternalApiToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A sign-in from a device the account has not used before leaves auth-service as one outbox row
 * on its own topic, written in the sign-in's own transaction. Through {@link TokenIssuer}, the one
 * place every sign-in path ends, against Postgres.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class NewDeviceSignInTest {

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
    private InternalApiToken internalApiToken;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private AuthSecurityEventRepository securityEventRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String CHROME_ON_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    @BeforeEach
    void cleanUp() {
        // Before the users they reference; the security log commits in its own transaction.
        jdbc.update("delete from known_devices");
        jdbc.update("delete from known_device_accounts");
        securityEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A request as api-gateway forwards it, from a London address, on Chrome on a Mac. */
    private void arrive(String deviceId, Map<String, String> extra) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Ip", "81.2.69.160");
        request.addHeader(InternalApiToken.HEADER, internalApiToken.value());
        request.addHeader("User-Agent", CHROME_ON_MAC);
        if (deviceId != null) {
            request.addHeader("X-Device-Id", deviceId);
        }
        extra.forEach(request::addHeader);
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

    private List<OutboxEvent> alerts() {
        return outboxEventRepository.findAll().stream()
                .filter(row -> "NEW_DEVICE".equals(row.getAggregateType()))
                .toList();
    }

    /** Oldest first. */
    private List<AuthSecurityEvent> signIns() {
        return securityEventRepository.findAll().stream()
                .filter(entry -> entry.getEventType() == SecurityEventType.LOGIN_SUCCEEDED)
                .sorted(Comparator.comparing(AuthSecurityEvent::getCreatedAt).thenComparing(AuthSecurityEvent::getId))
                .toList();
    }

    private int knownDevices() {
        return jdbc.queryForObject("select count(*) from known_devices", Integer.class);
    }

    @Test
    void aSecondDeviceSendsOneAlert_inFixedWords_withThePlace() {
        User user = saveUser(UserRole.USER);
        arrive("device-aaaaaaaaaaaa", Map.of());
        tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
        assertThat(alerts()).isEmpty();

        arrive("device-bbbbbbbbbbbb", Map.of(
                "Sec-CH-UA-Model", "\"routine check, no action needed\"",
                "Sec-CH-UA-Platform-Version", "\"27.0.0\""));
        tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);

        assertThat(alerts()).singleElement().satisfies(row -> {
            assertThat(row.getAggregateId()).isEqualTo(String.valueOf(user.getId()));
            assertThat(row.getEventType()).isEqualTo("NewDeviceSignInEvent");
            assertThat(row.getPayload())
                    .contains("\"deviceLabel\":\"Chrome on macOS 27\"", "\"location\":\"London, United Kingdom\"")
                    .doesNotContain("routine");
        });
        assertThat(signIns()).extracting(AuthSecurityEvent::getDetail).containsExactly(null, "New device");
    }

    @Test
    void aRefreshMakesTheBrowserKnown_soItsNextSignInIsQuiet() {
        User user = saveUser(UserRole.USER);
        arrive("device-aaaaaaaaaaaa", Map.of());
        tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);

        arrive("device-cccccccccccc", Map.of());
        tokenIssuer.reissue(user);
        tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);

        assertThat(alerts()).isEmpty();
    }

    /** An admin signs in only in the console, which shows no inbox: an alert would go nowhere. */
    @Test
    void anAdminIsLeftOutEntirely() {
        User admin = saveUser(UserRole.ADMIN);
        arrive("device-aaaaaaaaaaaa", Map.of());
        tokenIssuer.issue(admin, SecurityEventType.LOGIN_SUCCEEDED);
        arrive("device-bbbbbbbbbbbb", Map.of());
        tokenIssuer.issue(admin, SecurityEventType.LOGIN_SUCCEEDED);

        assertThat(alerts()).isEmpty();
        assertThat(knownDevices()).isZero();
    }

    /**
     * The device row and the alert's outbox row commit together, with the refresh token: a sign-in
     * that fails after issuing remembers nothing, so the device still alerts next time.
     */
    @Test
    void aSignInThatRollsBackLeavesNoAlertAndNoDevice() {
        User user = saveUser(UserRole.USER);
        arrive("device-aaaaaaaaaaaa", Map.of());
        tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
        arrive("device-bbbbbbbbbbbb", Map.of());

        TransactionTemplate caller = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> caller.executeWithoutResult(status -> {
            tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
            throw new IllegalStateException("the caller failed after issuing");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(alerts()).isEmpty();
        assertThat(knownDevices()).isEqualTo(1);
    }
}
