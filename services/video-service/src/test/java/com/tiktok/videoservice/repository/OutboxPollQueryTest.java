package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox poll is a derived query, so its whole behavior lives in the method name — a wrong
 * name still compiles and still returns rows, just the wrong ones. It is exercised against a real
 * Mongo rather than a repository mock for that reason.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OutboxPollQueryTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
    }

    @Test
    void pendingVideoIsPicked() {
        Video video = save(false);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .extracting(Video::getId)
                .containsExactly(video.getId());
    }

    @Test
    void deletedVideoIsSkipped() {
        save(true);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .as("a video deleted before the poll ran must never be announced")
                .isEmpty();
    }

    @Test
    void deletedVideoIsSkippedWhileALivePendingOneIsStillPicked() {
        save(true);
        Video live = save(false);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .extracting(Video::getId)
                .containsExactly(live.getId());
    }

    @Test
    void alreadyPublishedVideoIsSkipped() {
        Video video = save(false);
        video.markEventPublished();
        videoRepository.save(video);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .isEmpty();
    }

    /**
     * The poll runs every five seconds against the collection that only ever grows, and neither
     * of its filter fields is a prefix of the feed or profile indexes — so without an index of its
     * own it is a full scan plus an in-memory sort, which looks fine in every test and degrades
     * only in production. Asserted on the chosen plan rather than on the index list: an index that
     * exists but that the planner does not pick buys nothing.
     */
    @Test
    void theOutboxPollIsIndexed() {
        Document explain = mongoTemplate.getDb().runCommand(new Document("explain",
                new Document("find", "videos")
                        .append("filter", new Document("eventPublishedAt", null)
                                .append("eventFailedAt", null)
                                .append("deletedAt", null))
                        .append("sort", new Document("createdAt", 1))));

        assertThat(explain.toJson())
                .as("the poll must read an index, not walk the collection")
                .contains("IXSCAN")
                .contains("outbox_idx")
                .doesNotContain("SORT_KEY_GENERATOR");
    }

    /**
     * A row whose event could not be built parks at the head of the poll otherwise: the query
     * returns the oldest hundred unpublished videos every five seconds, so the same document
     * comes back in the same first position for ever and everything behind it waits on a retry
     * that cannot succeed.
     */
    @Test
    void parkedVideoIsSkippedWhileALivePendingOneIsStillPicked() {
        Video parked = save(false);
        parked.markEventFailed();
        videoRepository.save(parked);
        Video live = save(false);

        assertThat(videoRepository
                .findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .extracting(Video::getId)
                .containsExactly(live.getId());
    }

    /** No retention cutoff on the deletion itself: it is what takes the video out of search. */
    @Test
    void deletedVideoIsPickedByTheDeletionPollImmediately() {
        Video video = save(true);

        assertThat(videoRepository.findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc())
                .extracting(Video::getId)
                .containsExactly(video.getId());
    }

    @Test
    void liveVideoIsNotPickedByTheDeletionPoll() {
        save(false);

        assertThat(videoRepository.findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc())
                .isEmpty();
    }

    @Test
    void alreadyAnnouncedDeletionIsSkipped() {
        Video video = save(true);
        video.markDeleteEventPublished();
        videoRepository.save(video);

        assertThat(videoRepository.findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc())
                .isEmpty();
    }

    /** Same reasoning as {@link #theOutboxPollIsIndexed}, for the poll that runs beside it. */
    @Test
    void theDeletionPollIsIndexed() {
        Document explain = mongoTemplate.getDb().runCommand(new Document("explain",
                new Document("find", "videos")
                        .append("filter", new Document("deletedAt", new Document("$ne", null))
                                .append("deleteEventPublishedAt", null))
                        .append("sort", new Document("deletedAt", 1))));

        assertThat(explain.toJson())
                .as("the deletion poll must read an index, not walk the collection")
                .contains("IXSCAN")
                .contains("delete_outbox_idx")
                .doesNotContain("SORT_KEY_GENERATOR");
    }

    @Test
    void deletedVideoIsPickedByThePurgePollOnceRetentionExpires() {
        Video video = save(true);

        assertThat(videoRepository.findPendingPurge(
                        Instant.now().plusSeconds(60), 100))
                .extracting(Video::getId)
                .containsExactly(video.getId());
    }

    /**
     * The trash window: a video stays out of this poll — and its media stays in MinIO — until
     * {@code purgeBefore} catches up to its deletedAt, which is what lets an admin still watch it.
     */
    @Test
    void deletedVideoStillInsideRetentionIsNotPickedByThePurgePoll() {
        save(true);

        assertThat(videoRepository.findPendingPurge(
                        Instant.now().minusSeconds(60), 100))
                .isEmpty();
    }

    @Test
    void liveVideoIsNotPickedByThePurgePoll() {
        save(false);

        assertThat(videoRepository.findPendingPurge(
                        Instant.now().plusSeconds(60), 100))
                .isEmpty();
    }

    /** The two slots are independent: an announced deletion is still waiting on its purge. */
    @Test
    void announcedDeletionIsStillPickedByThePurgePoll() {
        Video video = save(true);
        video.markDeleteEventPublished();
        videoRepository.save(video);

        assertThat(videoRepository.findPendingPurge(
                        Instant.now().plusSeconds(60), 100))
                .extracting(Video::getId)
                .containsExactly(video.getId());
    }

    @Test
    void alreadyAnnouncedPurgeIsSkipped() {
        Video video = save(true);
        video.markPurgeEventPublished();
        videoRepository.save(video);

        assertThat(videoRepository.findPendingPurge(
                        Instant.now().plusSeconds(60), 100))
                .isEmpty();
    }

    @Test
    void thePurgePollIsIndexed() {
        Document explain = mongoTemplate.getDb().runCommand(new Document("explain",
                new Document("find", "videos")
                        .append("filter", new Document("deletedAt",
                                        new Document("$ne", null).append("$lt", Instant.now()))
                                .append("purgeEventPublishedAt", null))
                        .append("sort", new Document("deletedAt", 1))));

        assertThat(explain.toJson())
                .as("the purge poll must read an index, not walk the collection")
                .contains("IXSCAN")
                .contains("purge_outbox_idx")
                .doesNotContain("SORT_KEY_GENERATOR");
    }

    private Video save(boolean deleted) {
        String id = Video.newId();
        Video video = Video.builder()
                .id(id)
                .userId(1L)
                .title("t")
                // Distinct per video: rawFileUrl is uniquely indexed, because one upload is one
                // video. Sharing a key across fixtures is the thing that index exists to stop.
                .rawFileUrl("s3://video-media/raw/1/%s.mp4".formatted(id))
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build();
        if (deleted) {
            video.markDeleted();
        }
        return videoRepository.save(video);
    }
}
