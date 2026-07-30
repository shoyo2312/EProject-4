package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
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
class VideoTranscodedEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoTranscodedEventConsumer consumer;

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
    void onMessage_success_marksVideoPublished() throws Exception {
        Video video = videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build());

        VideoTranscodedEvent event = VideoTranscodedEvent.success(
                video.getId(), "http://minio/thumb.jpg", "http://minio/master.m3u8", 42);
        consumer.onMessage(objectMapper.writeValueAsString(event));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
        assertThat(updated.getThumbnailUrl()).isEqualTo("http://minio/thumb.jpg");
        assertThat(updated.getHlsUrl()).isEqualTo("http://minio/master.m3u8");
        assertThat(updated.getDurationSeconds()).isEqualTo(42);
    }

    @Test
    void onMessage_failure_marksVideoFailed() throws Exception {
        Video video = videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://raw/2.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build());

        VideoTranscodedEvent event = VideoTranscodedEvent.failure(video.getId(), "boom");
        consumer.onMessage(objectMapper.writeValueAsString(event));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.FAILED);
    }

    @Test
    void onMessage_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://raw/3.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build());

        VideoTranscodedEvent event = VideoTranscodedEvent.success(
                video.getId(), "http://minio/thumb.jpg", "http://minio/master.m3u8", 42);
        String payload = objectMapper.writeValueAsString(event);

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }
}
