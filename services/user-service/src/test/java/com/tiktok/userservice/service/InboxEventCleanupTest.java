package com.tiktok.userservice.service;

import com.tiktok.userservice.entity.InboxEvent;
import com.tiktok.userservice.repository.InboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * inbox_events only ever grew: a row per consumed event, kept forever, on the table every consumer
 * must insert into before it may process anything.
 *
 * <p>What the cleanup has to get right is the keeping, not the deleting. A claim is the only thing
 * standing between a redelivered event and a second increment of the counter it moved, so anything
 * Kafka could still replay has to survive — which is why the grace is set from the broker's
 * retention rather than from how large the table has become.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class InboxEventCleanupTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private InboxEventCleanup cleanup;

    @Autowired
    private InboxEventRepository inboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        inboxEventRepository.deleteAll();
    }

    @Test
    void purge_removesClaimsKafkaCanNoLongerReplay_andKeepsTheRest() {
        insertClaim(1L, "just-processed", Instant.now());
        insertClaim(2L, "inside-grace", Instant.now().minus(Duration.ofDays(29)));
        insertClaim(3L, "beyond-grace", Instant.now().minus(Duration.ofDays(31)));

        cleanup.purgeProcessedEvents();

        assertThat(inboxEventRepository.findAll())
                .extracting(InboxEvent::getEventId)
                .as("a claim still inside the grace is what stops a redelivery from counting twice")
                .containsExactlyInAnyOrder("just-processed", "inside-grace");
    }

    @Test
    void purge_onAnAlreadyCleanTable_doesNothing() {
        insertClaim(1L, "recent", Instant.now());

        cleanup.purgeProcessedEvents();

        assertThat(inboxEventRepository.count()).isEqualTo(1);
    }

    /**
     * Written through JDBC rather than the repository because {@code InboxEvent.onCreate} stamps
     * {@code processed_at} with {@code Instant.now()} on every persist, so an aged row cannot be
     * produced through JPA at all.
     */
    private void insertClaim(long id, String eventId, Instant processedAt) {
        jdbcTemplate.update(
                "INSERT INTO inbox_events (id, event_id, event_type, processed_at) VALUES (?, ?, ?, ?)",
                id, eventId, "UserRegisteredEvent", Timestamp.from(processedAt));
    }
}
