package com.tiktok.analyticsservice.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real ClickHouse, because the property that matters — how many parts an insert leaves
 * behind — only exists there. One part per Kafka record is what fails under load with "too many
 * parts"; one per poll is the fix.
 *
 * <p>The listeners are loaded but never started: only their wiring is under test here.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@ActiveProfiles("test")
class ClickHouseBatchInsertTest {

    @Container
    static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");

    @DynamicPropertySource
    static void clickhouse(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:clickhouse://%s:%d/default?http_connection_provider=HTTP_URL_CONNECTION"
                .formatted(CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(8123)));
        registry.add("spring.datasource.username", CLICKHOUSE::getUsername);
        registry.add("spring.datasource.password", CLICKHOUSE::getPassword);
    }

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

    @Autowired
    private TrainingDataRepository trainingDataRepository;

    @Autowired
    private EngagementEventRepository engagementEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @BeforeEach
    void cleanUp() {
        for (String table : List.of("watch_events", "engagement_events", "video_tags")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
    }

    private long activeParts(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT count() FROM system.parts WHERE database = currentDatabase() AND table = ? AND active",
                Long.class, table);
    }

    private long rows(String table) {
        return jdbcTemplate.queryForObject("SELECT count() FROM " + table, Long.class);
    }

    @Test
    void aPollOfWatchSessionsIsOnePart() {
        trainingDataRepository.insertWatches(IntStream.range(0, 200)
                .mapToObj(i -> new TrainingDataRepository.WatchRow(
                        "evt-" + i, "v" + i, 7L, 9_000L, 10_000L, i % 2 == 0, T0.plusMillis(i)))
                .toList());

        assertThat(rows("watch_events")).isEqualTo(200);
        assertThat(activeParts("watch_events")).isEqualTo(1);
    }

    @Test
    void aPollOfEngagementIsOnePart() {
        engagementEventRepository.insertAll(IntStream.range(0, 50)
                .mapToObj(i -> new EngagementEventRepository.Row("evt-" + i, "LIKED", "v1", (long) i, T0))
                .toList());

        assertThat(rows("engagement_events")).isEqualTo(50);
        assertThat(activeParts("engagement_events")).isEqualTo(1);
    }

    @Test
    void aVideosTagsAreOnePart() {
        trainingDataRepository.insertTags("v1", List.of("dance", "music", "cats"), T0);

        assertThat(rows("video_tags")).isEqualTo(3);
        assertThat(activeParts("video_tags")).isEqualTo(1);
    }

    /**
     * The four streams that grow with use take whole polls; the rest take a record at a time. A
     * busy listener left on the default factory would still compile, and still write a part per
     * record.
     */
    @Test
    void theBusyStreamsAreConsumedInBatches() {
        Map<String, Boolean> batchByTopic = listeners.getListenerContainers().stream()
                .collect(Collectors.toMap(
                        container -> String.join(",", container.getContainerProperties().getTopics()),
                        ClickHouseBatchInsertTest::isBatch));

        assertThat(batchByTopic).containsEntry("interaction.watch-events", true)
                .containsEntry("interaction.like-events", true)
                .containsEntry("interaction.share-events", true)
                .containsEntry("interaction.comment-events", true)
                .containsEntry("video.video-events", false)
                .containsEntry("auth.user-events", false);
    }

    private static boolean isBatch(MessageListenerContainer container) {
        return container.getContainerProperties().getMessageListener() instanceof BatchMessageListener<?, ?>;
    }
}
