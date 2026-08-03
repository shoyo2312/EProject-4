package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
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
class CommentCreatedEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private CommentCreatedEventConsumer consumer;

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
    void onMessage_incrementsCommentCount() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        CommentCreatedEvent event = CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "nice video");
        consumer.onMessage(objectMapper.writeValueAsString(event));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getCommentCount()).isEqualTo(1);
    }

    @Test
    void onMessage_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(publishedVideo());
        String payload = objectMapper.writeValueAsString(
                CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "nice video"));

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getCommentCount()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    private Video publishedVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
