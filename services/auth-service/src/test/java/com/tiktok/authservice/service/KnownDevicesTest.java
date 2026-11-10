package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The spec's §6.3 rule, against Postgres: which sign-in tells the owner, and which stays quiet.
 * The decision is made by the database — unique keys and row counts — so these run against a real
 * one rather than a mock that would agree with whatever the code assumed.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class KnownDevicesTest {

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
    private KnownDevices devices;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String A = "device-aaaaaaaaaaaa";
    private static final String B = "device-bbbbbbbbbbbb";

    private TransactionTemplate transactionTemplate;
    private Long userId;

    @BeforeEach
    void setUp() {
        // Before the users they reference.
        jdbc.update("delete from known_devices");
        jdbc.update("delete from known_device_accounts");
        userRepository.deleteAll();

        transactionTemplate = new TransactionTemplate(transactionManager);
        userId = userRepository.save(User.builder()
                .username("johndoe")
                .email("john@example.com")
                .passwordHash("irrelevant")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build()).getId();
    }

    private Instant lastSeen(String deviceId) {
        return jdbc.queryForObject("select last_seen_at from known_devices where device_hash = ?",
                Timestamp.class, HashUtils.sha256(deviceId)).toInstant();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** Sign-up, and each account's first sign-in after this ships: nothing to compare against yet. */
    @Test
    void theFirstDeviceAnAccountIsSeenOnIsSilent() {
        assertThat(devices.signedIn(userId, A)).isFalse();
    }

    @Test
    void theSameDeviceAgainIsSilent_andIsTouched() {
        devices.signedIn(userId, A);
        Instant before = lastSeen(A);

        assertThat(devices.signedIn(userId, A)).isFalse();
        assertThat(lastSeen(A)).isAfter(before);
    }

    @Test
    void anotherDeviceAlerts() {
        devices.signedIn(userId, A);

        assertThat(devices.signedIn(userId, B)).isTrue();
    }

    /** The mobile app and hand-made calls send no id: true that it is no device seen before. */
    @Test
    void noIdAlertsOnlyOnceTheAccountHasHadADevice() {
        assertThat(devices.signedIn(userId, null)).isFalse();

        devices.signedIn(userId, A);

        assertThat(devices.signedIn(userId, null)).isTrue();
    }

    /** A browser signed in before the deploy becomes known on its next refresh, quietly. */
    @Test
    void aRefreshRemembersWithoutEverAlerting() {
        devices.refreshed(userId, A);
        assertThat(devices.signedIn(userId, A)).isFalse();

        devices.refreshed(userId, B);
        assertThat(devices.signedIn(userId, B)).isFalse();
    }

    @Test
    void deviceIdsAreStoredHashed() {
        devices.signedIn(userId, A);

        assertThat(jdbc.queryForList("select device_hash from known_devices", String.class))
                .containsExactly(HashUtils.sha256(A));
    }

    /**
     * A double submit from one new browser. Read-then-write would let both see the device as new
     * and alert twice; the unique key makes the second insert wait for the first and find it.
     */
    @Test
    void aDoubleSubmitFromOneNewDeviceAlertsOnce() throws Exception {
        devices.signedIn(userId, A);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> submit = () -> {
                start.await();
                return transactionTemplate.execute(status -> devices.signedIn(userId, B));
            };
            Future<Boolean> first = pool.submit(submit);
            Future<Boolean> second = pool.submit(submit);
            start.countDown();

            assertThat(List.of(first.get(30, SECONDS), second.get(30, SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every private window adds a row; the bound is by use, never by age alone. */
    @Test
    void theFiftyFirstDeviceEvictsTheLeastRecentlyUsed() {
        for (int i = 0; i < 50; i++) {
            devices.signedIn(userId, "device-%012d".formatted(i));
        }

        devices.signedIn(userId, "device-new-device-51");

        assertThat(count("known_devices")).isEqualTo(50);
        assertThat(devices.signedIn(userId, "device-%012d".formatted(0))).isTrue();
    }

    @Test
    void aRollbackLeavesNoDevice() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            devices.signedIn(userId, A);
            throw new IllegalStateException("sign-in failed after the device step");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("known_devices")).isZero();
        assertThat(count("known_device_accounts")).isZero();
    }
}
