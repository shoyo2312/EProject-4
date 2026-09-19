package com.tiktok.videoservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lost transcode result or moderation verdict — a consumer that gave up to its dead-letter
 * topic, a worker that died mid-job — left the video in PROCESSING or PENDING_MODERATION for good:
 * invisible to everyone, and with nothing telling the uploader it was never coming.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class StuckVideoSweeperTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private StuckVideoSweeper sweeper;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
    }

    @Test
    void aVideoThatNeverFinishedTranscoding_isReportedAsFailed() {
        String id = video(VideoStatus.PROCESSING, Duration.ofHours(5));

        sweeper.sweep();

        Video video = videoRepository.findById(id).orElseThrow();
        assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(video.getFailureReason()).isNotBlank();
    }

    /** Never PUBLISHED: a verdict that did not arrive is not a pass. A human decides instead. */
    @Test
    void aVideoThatNeverGotAVerdict_goesToHumanReview() {
        String id = video(VideoStatus.PENDING_MODERATION, Duration.ofHours(5));

        sweeper.sweep();

        Video video = videoRepository.findById(id).orElseThrow();
        assertThat(video.getStatus()).isEqualTo(VideoStatus.PENDING_REVIEW);
        assertThat(video.getModeration().getVerdict()).isEqualTo(ModerationVerdict.REVIEW);
    }

    @Test
    void videosStillWithinTheirWindow_andFinishedOnes_areLeftAlone() {
        String fresh = video(VideoStatus.PROCESSING, Duration.ofMinutes(10));
        String published = video(VideoStatus.PUBLISHED, Duration.ofDays(3));

        sweeper.sweep();

        assertThat(videoRepository.findById(fresh).orElseThrow().getStatus()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(videoRepository.findById(published).orElseThrow().getStatus()).isEqualTo(VideoStatus.PUBLISHED);
    }

    private String video(VideoStatus status, Duration age) {
        Video saved = videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1/" + Video.newId() + ".mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(status)
                .build());
        // Written after the save: auditing stamps createdAt on insert whatever the builder said.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("createdAt", Instant.now().minus(age)), Video.class);
        return saved.getId();
    }
}
