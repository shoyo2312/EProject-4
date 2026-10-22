package com.tiktok.storyservice.repository;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryModeration;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The activation stamp and the announcement poll against a real Mongo: the poll is a query, so a
 * mocked repository would only hand back what the test put in.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@ActiveProfiles("test")
class StoryRepositoryImplTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private StoryRepository storyRepository;

    @BeforeEach
    void cleanUp() {
        storyRepository.deleteAll();
    }

    @Test
    void decideToActiveStampsActivatedAt() {
        insertPending("s1");

        boolean decided = storyRepository.decide("s1", StoryStatus.ACTIVE,
                Instant.now().plus(Duration.ofHours(24)), null, moderation());

        assertThat(decided).isTrue();
        assertThat(storyRepository.findById("s1").orElseThrow().getActivatedAt())
                .isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void decideToHeldOrRejectedLeavesActivatedAtNull() {
        insertPending("held");
        insertPending("rejected");

        storyRepository.decide("held", StoryStatus.HELD, null, null, moderation());
        storyRepository.decide("rejected", StoryStatus.REJECTED, null, null, moderation());

        assertThat(storyRepository.findById("held").orElseThrow().getActivatedAt()).isNull();
        assertThat(storyRepository.findById("rejected").orElseThrow().getActivatedAt()).isNull();
    }

    @Test
    void announceableSkipsWhatMustNotBeAnnounced() {
        Instant now = Instant.now();
        Instant earlier = now.minusSeconds(60);
        // b goes in before a, so only the sort can put a first
        storyRepository.insert(live("b", StoryVisibility.FRIENDS).activatedAt(earlier).build());
        storyRepository.insert(live("a", StoryVisibility.PUBLIC).activatedAt(now.minusSeconds(120)).build());
        // live before activatedAt existed
        storyRepository.insert(live("c", StoryVisibility.PUBLIC).build());
        storyRepository.insert(live("d", StoryVisibility.PRIVATE).activatedAt(earlier).build());
        storyRepository.insert(live("e", StoryVisibility.PUBLIC).activatedAt(earlier).deletedAt(now).build());
        storyRepository.insert(live("f", StoryVisibility.PUBLIC).activatedAt(earlier).expiresAt(earlier).build());
        storyRepository.insert(live("g", StoryVisibility.PUBLIC).activatedAt(earlier).announcedAt(now).build());
        storyRepository.insert(live("h", StoryVisibility.PUBLIC).status(StoryStatus.HELD).activatedAt(earlier).build());

        assertThat(storyRepository.findAnnounceable(now, 100)).extracting(Story::getId).containsExactly("a", "b");
        assertThat(storyRepository.findAnnounceable(now, 1)).extracting(Story::getId).containsExactly("a");
    }

    @Test
    void markAnnouncedTakesAStoryOutOfThePoll() {
        Instant now = Instant.now();
        storyRepository.insert(live("a", StoryVisibility.PUBLIC).activatedAt(now.minusSeconds(120)).build());
        storyRepository.insert(live("b", StoryVisibility.FRIENDS).activatedAt(now.minusSeconds(60)).build());

        storyRepository.markAnnounced("a", now);

        assertThat(storyRepository.findAnnounceable(now, 100)).extracting(Story::getId).containsExactly("b");
    }

    private void insertPending(String id) {
        storyRepository.insert(Story.pending(id, 7L, mediaKey(id), StoryMediaType.IMAGE, null,
                StoryVisibility.PUBLIC, ModerationVerdict.APPROVED, Instant.now()));
    }

    /** An ACTIVE story with a day left, for the caller to adjust; one media key per id, the field is unique. */
    private static Story.StoryBuilder<?, ?> live(String id, StoryVisibility visibility) {
        return Story.builder()
                .id(id)
                .userId(7L)
                .mediaKey(mediaKey(id))
                .mediaType(StoryMediaType.IMAGE)
                .status(StoryStatus.ACTIVE)
                .visibility(visibility)
                .expiresAt(Instant.now().plus(Duration.ofHours(24)));
    }

    private static String mediaKey(String id) {
        return "stories/7/" + id + ".jpg";
    }

    private static StoryModeration moderation() {
        return StoryModeration.builder().verdict(ModerationVerdict.APPROVED).decidedAt(Instant.now()).build();
    }
}
