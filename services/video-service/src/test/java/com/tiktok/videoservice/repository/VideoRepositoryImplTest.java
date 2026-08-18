package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Every test here reproduces the same interleaving: something reads a Video, a like lands on it
 * via $inc, and only then does the reader write its own change back. Against the old
 * whole-document save() each of these throws OptimisticLockingFailureException — the $inc bumps
 * @Version, so the save's version filter no longer matches. See {@link VideoRepositoryCustom}
 * for what that cost at each call site.
 *
 * <p>Each case asserts both halves: the counter survived, and the change actually landed.
 * Asserting only the counter would also pass for a write that did nothing at all.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class VideoRepositoryImplTest {

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
    void updateTranscodeResult_keepsConcurrentLikes() {
        Video stale = givenVideoReadBeforeConcurrentLikes(VideoStatus.PROCESSING, 5);

        stale.markPublished("http://minio/thumb.jpg", "http://minio/master.m3u8", 42);
        videoRepository.updateTranscodeResult(stale);

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(5);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
        assertThat(after.getHlsUrl()).isEqualTo("http://minio/master.m3u8");
        assertThat(after.getThumbnailUrl()).isEqualTo("http://minio/thumb.jpg");
        assertThat(after.getDurationSeconds()).isEqualTo(42);
    }

    @Test
    void updateStatus_keepsConcurrentLikes() {
        Video stale = givenVideoReadBeforeConcurrentLikes(VideoStatus.PROCESSING, 3);

        stale.markFailed();
        videoRepository.updateStatus(stale);

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(3);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void updateStatus_moderation_keepsConcurrentLikes() {
        Video stale = givenVideoReadBeforeConcurrentLikes(VideoStatus.PUBLISHED, 7);

        stale.markTakenDown();
        videoRepository.updateStatus(stale);

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(7);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(after.getStatusBeforeTakedown()).isEqualTo(VideoStatus.PUBLISHED);
    }

    @Test
    void updateStatus_moderation_restoreClearsStatusBeforeTakedown() {
        Video video = save(VideoStatus.PUBLISHED);
        video.markTakenDown();
        videoRepository.updateStatus(video);

        Video takenDown = reload(video);
        takenDown.markRestored();
        videoRepository.updateStatus(takenDown);

        Video after = reload(video);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
        assertThat(after.getStatusBeforeTakedown()).isNull();
    }

    @Test
    void updateEventPublished_keepsConcurrentLikes() {
        Video stale = givenVideoReadBeforeConcurrentLikes(VideoStatus.PROCESSING, 2);

        stale.markEventPublished();
        videoRepository.updateEventPublished(stale);

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(2);
        assertThat(after.getEventPublishedAt()).isNotNull();
        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .isEmpty();
    }

    @Test
    void updateSoftDeleted_keepsConcurrentLikes() {
        Video stale = givenVideoReadBeforeConcurrentLikes(VideoStatus.PUBLISHED, 4);

        stale.markDeleted();
        videoRepository.updateSoftDeleted(stale);

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(4);
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(videoRepository.findByIdAndDeletedAtIsNull(stale.getId())).isEmpty();
    }

    @Test
    void fieldScopedUpdate_refreshesUpdatedAt() {
        Video video = save(VideoStatus.PROCESSING);
        var before = reload(video).getUpdatedAt();

        video.markFailed();
        videoRepository.updateStatus(video);

        // @LastModifiedDate auditing does not run for MongoTemplate updates, so the timestamp
        // is written by hand — without that it would silently freeze at creation time.
        assertThat(reload(video).getUpdatedAt()).isAfter(before);
    }

    /**
     * Returns the entity as it was read <em>before</em> the likes landed — the stale in-memory
     * copy whose write-back used to clobber them.
     */
    private Video givenVideoReadBeforeConcurrentLikes(VideoStatus status, long likes) {
        Video video = save(status);
        Video stale = reload(video);

        mongoTemplate.updateFirst(
                Query.query(where("_id").is(video.getId())),
                new Update().inc("likeCount", likes),
                Video.class);

        return stale;
    }

    private Video save(VideoStatus status) {
        return videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(status)
                .build());
    }

    private Video reload(Video video) {
        return videoRepository.findById(video.getId()).orElseThrow();
    }
}
