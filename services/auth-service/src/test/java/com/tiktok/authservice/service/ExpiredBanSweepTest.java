package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A temporary ban is only temporary because this sweep ends it, and the query behind it is the
 * whole risk: {@code banned_until < now} against a column that is NULL for every permanent ban.
 * Get that wrong and the job quietly unbans the accounts that were meant to stay gone — which no
 * test of the sweep's arithmetic alone would catch, so this one runs against a real database.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ExpiredBanSweepTest {

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
    private ExpiredBanSweep sweep;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void liftsABanThatHasRunOut() {
        Long id = banned("lapsed", Instant.now().minus(Duration.ofHours(1)));

        sweep.liftExpiredBans();

        User user = userRepository.findById(id).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getBannedUntil())
                .as("a cleared deadline is what stops the row coming back on the next sweep")
                .isNull();
        assertThat(user.getBannedAt()).isNull();
        assertThat(user.getBanReason()).isNull();
    }

    @Test
    void leavesAPermanentBanAlone() {
        Long id = banned("forever", null);

        sweep.liftExpiredBans();

        assertThat(userRepository.findById(id).orElseThrow().getStatus())
                .as("banned_until IS NULL is a ban with no end, not one that ended long ago")
                .isEqualTo(UserStatus.BANNED);
    }

    @Test
    void leavesABanThatStillHasTimeOnIt() {
        Long id = banned("still-serving", Instant.now().plus(Duration.ofDays(3)));

        sweep.liftExpiredBans();

        assertThat(userRepository.findById(id).orElseThrow().getStatus()).isEqualTo(UserStatus.BANNED);
    }

    /** LOCKED is somebody else's decision; a lapsed ban deadline must not be read as lifting it. */
    @Test
    void leavesALockedAccountAlone() {
        User locked = userRepository.save(User.builder()
                .username("locked")
                .email("locked@example.com")
                .passwordHash("irrelevant")
                .role(UserRole.USER)
                .status(UserStatus.LOCKED)
                .build());

        sweep.liftExpiredBans();

        assertThat(userRepository.findById(locked.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.LOCKED);
    }

    private Long banned(String username, Instant until) {
        User user = User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash("irrelevant")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
        user.ban("spam", until);
        return userRepository.save(user).getId();
    }
}
