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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        assertThat(videoRepository.updateTranscodeResult(stale, VideoStatus.PROCESSING)).isTrue();

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
        assertThat(videoRepository.updateStatus(stale, VideoStatus.PROCESSING)).isTrue();

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(3);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void updateStatus_moderation_keepsConcurrentLikes() {
        Video stale = givenVideoReadBeforeConcurrentLikes(VideoStatus.PUBLISHED, 7);

        stale.markTakenDown();
        assertThat(videoRepository.updateStatus(stale, VideoStatus.PUBLISHED)).isTrue();

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(7);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(after.getStatusBeforeTakedown()).isEqualTo(VideoStatus.PUBLISHED);
    }

    @Test
    void updateStatus_moderation_restoreClearsStatusBeforeTakedown() {
        Video video = save(VideoStatus.PUBLISHED);
        video.markTakenDown();
        videoRepository.updateStatus(video, VideoStatus.PUBLISHED);

        Video takenDown = reload(video);
        takenDown.markRestored();
        videoRepository.updateStatus(takenDown, VideoStatus.TAKEN_DOWN);

        Video after = reload(video);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
        assertThat(after.getStatusBeforeTakedown()).isNull();
    }

    /**
     * The interleaving that costs a takedown: the transcode consumer reads a PROCESSING video, a
     * moderator takes it down while the transcode is still running, and the consumer then writes
     * PUBLISHED from what it read minutes ago. Unconditional, that write wins by arriving last
     * and the video is back on the feed with nothing recording it was ever removed.
     */
    @Test
    void updateTranscodeResult_whenAModeratorGotThereFirst_doesNotLand() {
        Video video = save(VideoStatus.PROCESSING);
        Video staleReadByTranscode = reload(video);

        Video readByModerator = reload(video);
        readByModerator.markTakenDown();
        assertThat(videoRepository.updateStatus(readByModerator, VideoStatus.PROCESSING)).isTrue();

        staleReadByTranscode.markPublished("http://minio/thumb.jpg", "http://minio/master.m3u8", 42);

        assertThat(videoRepository.updateTranscodeResult(staleReadByTranscode, VideoStatus.PROCESSING))
                .as("the status moved after it was read, so this write must be refused")
                .isFalse();

        Video after = reload(video);
        assertThat(after.getStatus()).isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(after.getHlsUrl())
                .as("a refused write must leave every field alone, not only status")
                .isNull();
    }

    @Test
    void updateEventPublished_keepsConcurrentLikes() {
        Video stale = givenVideoReadBeforeConcurrentLikes(VideoStatus.PROCESSING, 2);

        stale.markEventPublished();
        videoRepository.updateEventPublished(stale);

        Video after = reload(stale);
        assertThat(after.getLikeCount()).isEqualTo(2);
        assertThat(after.getEventPublishedAt()).isNotNull();
        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
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
        videoRepository.updateStatus(video, VideoStatus.PROCESSING);

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

    /**
     * The other half of the same race. VideoStateUpdater checks deletedAt when it reads, but a
     * transcode runs for minutes and an owner can delete inside that window — so the check has to
     * be in the write filter too. Without it the stale write lands on a deleted document and
     * leaves it both deleted and PUBLISHED, which is the combination VideoStateUpdater's own
     * javadoc says nothing later corrects.
     */
    @Test
    void updateStatus_whenTheVideoWasDeletedAfterItWasRead_doesNotLand() {
        Video video = save(VideoStatus.PROCESSING);
        Video staleReadByTranscode = reload(video);

        Video readByOwner = reload(video);
        readByOwner.markDeleted();
        videoRepository.updateSoftDeleted(readByOwner);

        staleReadByTranscode.markPublished("http://minio/thumb.jpg", "http://minio/master.m3u8", 42);

        assertThat(videoRepository.updateTranscodeResult(staleReadByTranscode, VideoStatus.PROCESSING))
                .as("the video was deleted after it was read, so this write must be refused")
                .isFalse();

        Video after = reload(video);
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(after.getStatus())
                .as("a deleted video must never come back as PUBLISHED")
                .isEqualTo(VideoStatus.PROCESSING);
        assertThat(after.getHlsUrl()).isNull();
    }

    /**
     * One upload is one video. A client retrying POST /videos after a timeout would otherwise get
     * a second document, a second VideoPublishedEvent, and a second transcode job off one file,
     * with nothing downstream able to tell the copies apart.
     */
    @Test
    void save_theSameRawFileTwice_isRefusedByTheIndex() {
        String rawFileUrl = "s3://video-media/raw/1/shared.mp4";
        save(VideoStatus.PROCESSING, rawFileUrl);

        assertThatThrownBy(() -> save(VideoStatus.PROCESSING, rawFileUrl))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * Deleting a video does not hand its raw object back for republishing. Scoping the index to
     * undeleted rows would, and would also need a partialFilterExpression that cannot test a
     * field for null.
     */
    @Test
    void save_theSameRawFileAfterADelete_isStillRefused() {
        String rawFileUrl = "s3://video-media/raw/1/deleted-then-reused.mp4";
        Video first = save(VideoStatus.PUBLISHED, rawFileUrl);
        first.markDeleted();
        videoRepository.updateSoftDeleted(first);

        assertThatThrownBy(() -> save(VideoStatus.PROCESSING, rawFileUrl))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private Video save(VideoStatus status) {
        return save(status, "s3://video-media/raw/1/%s.mp4".formatted(Video.newId()));
    }

    private Video save(VideoStatus status, String rawFileUrl) {
        return videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl(rawFileUrl)
                .visibility(VideoVisibility.PUBLIC)
                .status(status)
                .build());
    }

    private Video reload(Video video) {
        return videoRepository.findById(video.getId()).orElseThrow();
    }
}
