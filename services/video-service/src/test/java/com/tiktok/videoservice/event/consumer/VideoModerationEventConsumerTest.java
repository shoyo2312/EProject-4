package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class VideoModerationEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoModerationEventConsumer consumer;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void onMessage_approved_publishesTheVideoAndKeepsTheScores() throws Exception {
        Video video = givenTranscodedVideo();

        consumer.onMessage(objectMapper.writeValueAsString(VideoModerationCompletedEvent.of(
                video.getId(), ModerationVerdict.APPROVED, "nsfw", 0.03, 0, 10, 900L,
                "Falconsai/nsfw_image_detection", "nsfw-v1", null)));

        Video after = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
        // Kept on the document, not only in a log: tuning the thresholds means comparing what the
        // model said against what a moderator later decided, months after the log rolled over.
        assertThat(after.getModeration().getMaxScore()).isEqualTo(0.03);
        assertThat(after.getModeration().getModelVersion()).isEqualTo("nsfw-v1");
    }

    @Test
    void onMessage_review_holdsTheVideoForAnAdmin() throws Exception {
        Video video = givenTranscodedVideo();

        consumer.onMessage(objectMapper.writeValueAsString(VideoModerationCompletedEvent.of(
                video.getId(), ModerationVerdict.REVIEW, "nsfw", 0.72, 1, 10, 900L, "m", "v", null)));

        Video after = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(VideoStatus.PENDING_REVIEW);
    }

    @Test
    void onMessage_rejected_removesTheVideoWithoutAnAdmin() throws Exception {
        Video video = givenTranscodedVideo();

        consumer.onMessage(objectMapper.writeValueAsString(VideoModerationCompletedEvent.of(
                video.getId(), ModerationVerdict.REJECTED, "nsfw", 0.97, 3, 10, 900L, "m", "v", null)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus())
                .isEqualTo(VideoStatus.REJECTED);
    }

    /** The fail-safe as it arrives here: a check that could not run is a human's problem, not a pass. */
    @Test
    void onMessage_unavailable_holdsTheVideoAndSaysWhy() throws Exception {
        Video video = givenTranscodedVideo();

        consumer.onMessage(objectMapper.writeValueAsString(VideoModerationCompletedEvent.unavailable(
                video.getId(), "Automatic moderation could not be completed after 3 attempts")));

        Video after = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(VideoStatus.PENDING_REVIEW);
        assertThat(after.getModeration().getReason()).contains("could not be completed");
    }

    /**
     * An admin who took the video down while the classifier was still scoring it has already made
     * the decision it was about to make. An APPROVED landing afterwards must not undo it.
     */
    @Test
    void onMessage_whenAnAdminGotThereFirst_theTakedownSurvives() throws Exception {
        Video video = givenTranscodedVideo();
        video.markTakenDown("policy violation");
        videoRepository.updateStatus(video, VideoStatus.PENDING_MODERATION);

        consumer.onMessage(objectMapper.writeValueAsString(VideoModerationCompletedEvent.of(
                video.getId(), ModerationVerdict.APPROVED, "nsfw", 0.01, 0, 10, 900L, "m", "v", null)));

        Video after = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(after.getStatusBeforeTakedown())
                .as("a restore returns it to what the classifier decided, not to PENDING_MODERATION")
                .isEqualTo(VideoStatus.PUBLISHED);
    }

    @Test
    void onMessage_redelivered_isAppliedOnce() throws Exception {
        Video video = givenTranscodedVideo();
        String payload = objectMapper.writeValueAsString(VideoModerationCompletedEvent.of(
                video.getId(), ModerationVerdict.APPROVED, "nsfw", 0.01, 0, 10, 900L, "m", "v", null));

        consumer.onMessage(payload);
        // The second delivery finds the video PUBLISHED, not PENDING_MODERATION, so it would lose
        // the conditional write and exhaust the updater's retries if it were applied at all.
        consumer.onMessage(payload);

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus())
                .isEqualTo(VideoStatus.PUBLISHED);
    }

    private Video givenTranscodedVideo() {
        return videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PENDING_MODERATION)
                .build());
    }
}
