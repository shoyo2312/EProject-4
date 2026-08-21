package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoLikeEvent;
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
class VideoLikeEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoLikeEventConsumer consumer;

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
    void onMessage_liked_incrementsLikeCount() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        VideoLikeEvent event = VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, true);
        consumer.onMessage(objectMapper.writeValueAsString(event));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getLikeCount()).isEqualTo(1);
    }

    @Test
    void onMessage_unliked_decrementsLikeCount() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, true)));
        consumer.onMessage(objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, false)));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getLikeCount()).isEqualTo(0);
    }

    @Test
    void onMessage_unknownVideo_isNoOp() throws Exception {
        VideoLikeEvent event = VideoLikeEvent.of(999L, 1L, true);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    void onMessage_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(publishedVideo());
        String payload = objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, true));

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getLikeCount()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    /**
     * interaction-service is only as fresh as the last event it saw, so likes keep arriving for a
     * video its owner has already deleted. Counting them moves a number no read path will ever
     * show and nothing later corrects.
     */
    @Test
    void onMessage_softDeletedVideo_leavesTheCountAlone() throws Exception {
        Video video = publishedVideo();
        video.markDeleted();
        videoRepository.save(video);

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, true)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getLikeCount()).isEqualTo(0);
    }

    /**
     * $inc has no floor and the events do not reliably pair up: two partitions can deliver an
     * unlike ahead of its like, and a redelivery older than the claim TTL is applied twice. One
     * stray -1 would leave a video advertising -1 like for good, since nothing recomputes it.
     */
    @Test
    void onMessage_unlikedWithNoLikesCounted_leavesTheCountAtZero() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, false)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getLikeCount()).isZero();
    }

    /** The floor must not cost an unlike that does have a like to remove. */
    @Test
    void onMessage_unlikedWithLikesCounted_stillDecrements() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, true)));
        consumer.onMessage(objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 2L, false)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getLikeCount()).isZero();
    }

    /**
     * Same reason as the soft-deleted case above: a moderated video is off every read path, so
     * likes still arriving are stale clients, and the count they build stays invisible right up
     * until a restore puts the video back showing a number nobody can account for.
     */
    @Test
    void onMessage_takenDownVideo_leavesTheCountAlone() throws Exception {
        Video video = publishedVideo();
        video.markTakenDown();
        videoRepository.save(video);

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoLikeEvent.of(Long.valueOf(video.getId()), 1L, true)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getLikeCount()).isZero();
    }

    private Video publishedVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1/%s.mp4".formatted(Video.newId()))
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
