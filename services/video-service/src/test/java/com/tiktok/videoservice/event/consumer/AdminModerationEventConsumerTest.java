package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
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
class AdminModerationEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AdminModerationEventConsumer consumer;

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
    void onMessage_takenDown_marksVideoTakenDown() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://raw/1.mp4"));

        VideoTakenDownEvent event = VideoTakenDownEvent.of(video.getId(), 99L, "nudity");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoTakenDownEvent".getBytes());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.TAKEN_DOWN);
    }

    @Test
    void onMessage_restored_marksVideoPublished() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://raw/2.mp4"));
        video.markTakenDown();
        videoRepository.save(video);

        VideoRestoredEvent event = VideoRestoredEvent.of(video.getId(), 99L, "appeal accepted");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoRestoredEvent".getBytes());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
    }

    @Test
    void onMessage_restored_returnsToStatusBeforeTakedown() throws Exception {
        Video video = videoRepository.save(processingVideo());
        video.markTakenDown();
        videoRepository.save(video);

        VideoRestoredEvent event = VideoRestoredEvent.of(video.getId(), 99L, "appeal accepted");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoRestoredEvent".getBytes());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("a video taken down mid-transcode has no hlsUrl, so restore must not publish it")
                .isEqualTo(VideoStatus.PROCESSING);
    }

    @Test
    void onMessage_unrelatedEventType_isIgnored() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://raw/3.mp4"));

        consumer.onMessage("{\"userId\":1}", "UserBannedEvent".getBytes());

        Video unchanged = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
    }

    @Test
    void onMessage_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://raw/4.mp4"));

        VideoTakenDownEvent event = VideoTakenDownEvent.of(video.getId(), 99L, "spam");
        String payload = objectMapper.writeValueAsString(event);
        byte[] header = "VideoTakenDownEvent".getBytes();

        consumer.onMessage(payload, header);
        consumer.onMessage(payload, header);

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    private Video processingVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://raw/5.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build();
    }

    private Video publishedVideo(String rawFileUrl) {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl(rawFileUrl)
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
