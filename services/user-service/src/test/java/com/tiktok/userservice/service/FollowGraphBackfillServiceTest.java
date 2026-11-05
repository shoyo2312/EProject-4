package com.tiktok.userservice.service;

import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.repository.OutboxEventRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class FollowGraphBackfillServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private FollowGraphBackfillService backfill;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Well after every edge the tests make. */
    private static final Instant LATER = Instant.now().plusSeconds(3600);

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        userFollowRepository.deleteAll();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    private UserFollow edge(long follower, long following) {
        return userFollowRepository.save(UserFollow.builder().followerId(follower).followingId(following).build());
    }

    @Test
    void everyLiveEdgeIsPublishedOnceWithAStableId() throws Exception {
        UserFollow first = edge(1L, 2L);
        UserFollow second = edge(3L, 2L);
        UserFollow gone = edge(4L, 2L);
        gone.markDeleted();
        userFollowRepository.save(gone);

        long published = backfill.publishAll(LATER);

        assertThat(published).isEqualTo(2);
        List<OutboxEvent> rows = outboxEventRepository.findAll();
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.getAggregateType()).isEqualTo("USER_FOLLOW_BACKFILL");
            assertThat(row.getEventType()).isEqualTo("UserFollowChangedEvent");
        });
        for (UserFollow edge : List.of(first, second)) {
            UserFollowChangedEvent event = rows.stream()
                    .map(row -> objectMapper.readValue(row.getPayload(), UserFollowChangedEvent.class))
                    .filter(e -> e.eventId().equals("backfill-follow-" + edge.getId()))
                    .findFirst().orElseThrow();
            assertThat(event.followerId()).isEqualTo(edge.getFollowerId());
            assertThat(event.followingId()).isEqualTo(edge.getFollowingId());
            assertThat(event.followed()).isTrue();
            assertThat(event.requestApproved()).isFalse();
            assertThat(event.sourceVideoId()).isNull();
            assertThat(event.occurredAt()).isCloseTo(edge.getCreatedAt(), within(1, ChronoUnit.MILLIS));
        }
    }

    /** The outbox carries duplicates; ClickHouse's ReplacingMergeTree collapses them by event_id. */
    @Test
    void runningTwiceWritesTheSameIds() {
        edge(1L, 2L);
        edge(3L, 2L);

        backfill.publishAll(LATER);
        List<String> firstRun = outboxEventRepository.findAll().stream().map(OutboxEvent::getPayload).sorted().toList();
        outboxEventRepository.deleteAll();
        backfill.publishAll(LATER);
        List<String> secondRun = outboxEventRepository.findAll().stream().map(OutboxEvent::getPayload).sorted().toList();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    /** An edge from after the consumer shipped already has its live row; replaying it would count it twice. */
    @Test
    void edgesFromAfterTheCutoffAreLeftAlone() {
        UserFollow old = edge(1L, 2L);
        UserFollow recent = edge(3L, 2L);
        Instant cutoff = Instant.parse("2026-10-23T00:00:00Z");
        jdbcTemplate.update("UPDATE user_follows SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(cutoff.minusSeconds(1)), old.getId());

        assertThat(backfill.publishAll(cutoff)).isEqualTo(1);
        assertThat(outboxEventRepository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getPayload()).contains("backfill-follow-" + old.getId())
                        .doesNotContain("backfill-follow-" + recent.getId()));
    }
}
