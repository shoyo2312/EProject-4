package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.StoryReaction;
import com.tiktok.storyservice.entity.StoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The reaction upsert and the count against a real Mongo: both are queries a mock would only echo. */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@ActiveProfiles("test")
class StoryViewRepositoryImplTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private StoryViewRepository storyViewRepository;

    private final Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void cleanUp() {
        storyViewRepository.deleteAll();
    }

    @Test
    void firstReactionCreatesTheViewRow_laterOnesOnlyChangeIt() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        assertThat(storyViewRepository.upsertReaction("s1", 2L, StoryReaction.LOVE, expiresAt, now)).isTrue();
        assertThat(storyViewRepository.upsertReaction("s1", 2L, StoryReaction.WOW, expiresAt, now.plusSeconds(5))).isFalse();

        StoryView view = storyViewRepository.findByViewerIdAndStoryIdIn(2L, java.util.List.of("s1")).getFirst();
        assertThat(view.getReaction()).isEqualTo(StoryReaction.WOW);
        assertThat(view.getViewedAt()).isEqualTo(now);
        assertThat(view.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void reactionOnAnExistingViewKeepsItsViewedAt() {
        // `viewedAt` is @CreatedDate: auditing stamps it on insert, so read it back rather than assume it.
        Instant viewedAt = storyViewRepository
                .insert(StoryView.builder().storyId("s1").viewerId(2L).expiresAt(expiresAt).build()).getViewedAt();

        assertThat(storyViewRepository.upsertReaction("s1", 2L, StoryReaction.SAD, expiresAt, Instant.now().plusSeconds(60))).isFalse();

        StoryView view = storyViewRepository.findByViewerIdAndStoryIdIn(2L, java.util.List.of("s1")).getFirst();
        assertThat(view.getViewedAt()).isEqualTo(viewedAt.truncatedTo(ChronoUnit.MILLIS));
        assertThat(view.getReaction()).isEqualTo(StoryReaction.SAD);
    }

    @Test
    void countsEachReactionAndSkipsViewsWithout() {
        Instant now = Instant.now();
        storyViewRepository.upsertReaction("s1", 2L, StoryReaction.LOVE, expiresAt, now);
        storyViewRepository.upsertReaction("s1", 3L, StoryReaction.LOVE, expiresAt, now);
        storyViewRepository.upsertReaction("s1", 4L, StoryReaction.ANGRY, expiresAt, now);
        storyViewRepository.upsertReaction("s2", 2L, StoryReaction.SAD, expiresAt, now);
        storyViewRepository.insert(StoryView.builder().storyId("s1").viewerId(5L).viewedAt(now).expiresAt(expiresAt).build());

        assertThat(storyViewRepository.countReactions("s1"))
                .containsExactlyInAnyOrderEntriesOf(Map.of(StoryReaction.LOVE, 2L, StoryReaction.ANGRY, 1L));
        assertThat(storyViewRepository.countReactions("none")).isEmpty();
    }

    @Test
    void clearingLeavesTheViewAndIsIdempotent() {
        storyViewRepository.upsertReaction("s1", 2L, StoryReaction.LIKE, expiresAt, Instant.now());

        storyViewRepository.clearReaction("s1", 2L);
        storyViewRepository.clearReaction("s1", 2L);

        StoryView view = storyViewRepository.findByViewerIdAndStoryIdIn(2L, java.util.List.of("s1")).getFirst();
        assertThat(view.getReaction()).isNull();
        assertThat(view.getReactedAt()).isNull();
        assertThat(storyViewRepository.countReactions("s1")).isEmpty();
    }
}
