package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.entity.VerificationToken;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * The three tables here only ever grew — a row per login, per refresh, per OTP, per registration,
 * kept forever. What the cleanup must get right is not the deleting but the keeping: a live
 * session, an OTP a user is about to type, and above all an outbox row Kafka has not taken yet.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ExpiredRecordCleanupTest {

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
    private ExpiredRecordCleanup cleanup;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private VerificationTokenRepository verificationTokenRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** verification_tokens carries a foreign key to users, so the rows need a real owner. */
    private Long userId;

    @BeforeEach
    void cleanUp() {
        refreshTokenRepository.deleteAll();
        verificationTokenRepository.deleteAll();
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();

        userId = userRepository.save(User.builder()
                .username("johndoe")
                .email("john@example.com")
                .passwordHash("irrelevant")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build()).getId();
    }

    @Test
    void purge_removesLongExpiredRefreshTokens_andLeavesLiveOnes() {
        RefreshToken live = saveRefreshToken("live", Instant.now().plus(Duration.ofDays(7)));
        RefreshToken justExpired = saveRefreshToken("just-expired", Instant.now().minus(Duration.ofHours(1)));
        RefreshToken longGone = saveRefreshToken("long-gone", Instant.now().minus(Duration.ofDays(30)));

        cleanup.purgeExpiredRecords();

        assertThat(refreshTokenRepository.findAll())
                .extracting(RefreshToken::getId)
                .as("a token still inside the grace is the audit trail for a session that just ended")
                .containsExactlyInAnyOrder(live.getId(), justExpired.getId())
                .doesNotContain(longGone.getId());
    }

    @Test
    void purge_removesLongExpiredOtps_andLeavesOnesStillInPlay() {
        VerificationToken usable = saveVerificationToken("usable", Instant.now().plus(Duration.ofMinutes(15)));
        VerificationToken stale = saveVerificationToken("stale", Instant.now().minus(Duration.ofDays(30)));

        cleanup.purgeExpiredRecords();

        assertThat(verificationTokenRepository.findAll())
                .extracting(VerificationToken::getId)
                .containsExactly(usable.getId())
                .doesNotContain(stale.getId());
    }

    /**
     * The one that matters. An unpublished outbox row is an event that exists nowhere else, so
     * age is never a reason to delete it — a row stuck for a month is a publisher to go and fix,
     * not garbage. Deleting it would be exactly the loss the outbox pattern exists to prevent.
     */
    @Test
    void purge_neverRemovesAnUnpublishedOutboxRow_howeverOldItIs() {
        OutboxEvent stuck = saveOutboxEvent("stuck");
        backdateColumn("outbox_events", "created_at", stuck.getId(), Duration.ofDays(365));

        OutboxEvent delivered = saveOutboxEvent("delivered");
        markPublished(delivered.getId(), Duration.ofDays(30));

        OutboxEvent deliveredJustNow = saveOutboxEvent("delivered-just-now");
        markPublished(deliveredJustNow.getId(), Duration.ZERO);

        cleanup.purgeExpiredRecords();

        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getId)
                .as("a year-old row Kafka never took is a bug to investigate, not one to erase")
                .containsExactlyInAnyOrder(stuck.getId(), deliveredJustNow.getId())
                .doesNotContain(delivered.getId());
    }

    /**
     * More rows than one batch holds, so the loop has to come back for the rest instead of
     * stopping at the first thousand.
     */
    @Test
    void purge_keepsGoingPastASingleBatch() {
        for (int i = 0; i < 1200; i++) {
            saveRefreshToken("expired-" + i, Instant.now().minus(Duration.ofDays(30)));
        }

        cleanup.purgeExpiredRecords();

        assertThat(refreshTokenRepository.count()).isZero();
    }

    private RefreshToken saveRefreshToken(String hash, Instant expiresAt) {
        return refreshTokenRepository.save(RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash)
                .expiresAt(expiresAt)
                .build());
    }

    private VerificationToken saveVerificationToken(String hash, Instant expiresAt) {
        return verificationTokenRepository.save(VerificationToken.builder()
                .userId(userId)
                .tokenHash(hash)
                .tokenType(VerificationTokenType.EMAIL_VERIFICATION)
                .expiresAt(expiresAt)
                .build());
    }

    private OutboxEvent saveOutboxEvent(String aggregateId) {
        return outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType("User")
                .aggregateId(aggregateId)
                .eventType("UserRegistered")
                .payload("{}")
                .build());
    }

    /** Publication timestamps are set by the publisher, so ageing one takes SQL. */
    private void markPublished(Long id, Duration ago) {
        jdbcTemplate.update("UPDATE outbox_events SET published_at = now() - CAST(? AS interval) WHERE id = ?",
                ago.toSeconds() + " seconds", id);
    }

    private void backdateColumn(String table, String column, Long id, Duration by) {
        jdbcTemplate.update("UPDATE " + table + " SET " + column + " = now() - CAST(? AS interval) WHERE id = ?",
                by.toSeconds() + " seconds", id);
    }
}
