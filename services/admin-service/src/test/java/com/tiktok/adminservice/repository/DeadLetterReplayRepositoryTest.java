package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.DeadLetterReplay;
import com.tiktok.kafka.dlt.ReplayResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Against V8 itself: Hibernate validates the entity on the way in. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DeadLetterReplayRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DeadLetterReplayRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findsTheRowsOfTheListedOffsetsOnOneTopic() {
        Instant parkedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DeadLetterReplay listed = replay("video.video-events.DLT", 4, parkedAt);
        replay("video.video-events.DLT", 9, parkedAt);
        replay("user.user-events.DLT", 4, parkedAt);
        entityManager.flush();
        entityManager.clear();

        List<DeadLetterReplay> found = repository.findByDltTopicAndDltOffsetIn("video.video-events.DLT", List.of(4L, 5L));

        assertThat(found).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(listed.getId());
            assertThat(row.getParkedAt()).isEqualTo(parkedAt);
            assertThat(row.getOutcome()).isEqualTo(ReplayResult.Outcome.HELD_BACK);
        });
    }

    private DeadLetterReplay replay(String topic, long offset, Instant parkedAt) {
        return entityManager.persist(DeadLetterReplay.builder()
                .adminId(7L)
                .dltTopic(topic)
                .dltPartition(0)
                .dltOffset(offset)
                .parkedAt(parkedAt)
                .outcome(ReplayResult.Outcome.HELD_BACK)
                .detail("video.video-events-0@12 holds a later VideoRestoredEvent for key v1")
                .forced(false)
                .build());
    }
}
