package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class CommentEventConsumerTest {

    private static final byte[] CREATED_HEADER = "CommentCreatedEvent".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELETED_HEADER = "CommentDeletedEvent".getBytes(StandardCharsets.UTF_8);

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private CommentEventConsumer consumer;

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
    void onMessage_created_incrementsCommentCount() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        CommentCreatedEvent event = CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "nice video");
        consumer.onMessage(objectMapper.writeValueAsString(event), CREATED_HEADER);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getCommentCount()).isEqualTo(1);
    }

    /** Absent header means a producer older than the deletion event, which only ever created. */
    @Test
    void onMessage_absentHeader_treatedAsCreated() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        CommentCreatedEvent event = CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "nice video");
        consumer.onMessage(objectMapper.writeValueAsString(event), null);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getCommentCount()).isEqualTo(1);
    }

    @Test
    void onMessage_created_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(publishedVideo());
        String payload = objectMapper.writeValueAsString(
                CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "nice video"));

        consumer.onMessage(payload, CREATED_HEADER);
        consumer.onMessage(payload, CREATED_HEADER);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getCommentCount()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    /** Same reasoning as VideoLikeEventConsumerTest: a deleted video's counters stay frozen. */
    @Test
    void onMessage_created_softDeletedVideo_leavesTheCountAlone() throws Exception {
        Video video = publishedVideo();
        video.markDeleted();
        videoRepository.save(video);

        consumer.onMessage(objectMapper.writeValueAsString(
                CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "nice video")), CREATED_HEADER);

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getCommentCount()).isEqualTo(0);
    }

    @Test
    void onMessage_deleted_decrementsCommentCount() throws Exception {
        Video video = videoRepository.save(publishedVideo());
        consumer.onMessage(objectMapper.writeValueAsString(
                CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "nice video")), CREATED_HEADER);

        CommentDeletedEvent event = CommentDeletedEvent.of(1L, Long.valueOf(video.getId()), 1L);
        consumer.onMessage(objectMapper.writeValueAsString(event), DELETED_HEADER);

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getCommentCount()).isEqualTo(0);
    }

    @Test
    void onMessage_deleted_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(publishedVideo());
        consumer.onMessage(objectMapper.writeValueAsString(
                CommentCreatedEvent.of(1L, Long.valueOf(video.getId()), 1L, "a")), CREATED_HEADER);
        consumer.onMessage(objectMapper.writeValueAsString(
                CommentCreatedEvent.of(2L, Long.valueOf(video.getId()), 1L, "b")), CREATED_HEADER);
        String payload = objectMapper.writeValueAsString(
                CommentDeletedEvent.of(1L, Long.valueOf(video.getId()), 1L));

        consumer.onMessage(payload, DELETED_HEADER);
        consumer.onMessage(payload, DELETED_HEADER);

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getCommentCount()).isEqualTo(1);
    }

    /**
     * $inc has no floor and the two events do not reliably pair up: a redelivery outside the
     * idempotency claim's window would otherwise push the count below what was ever really there
     * — same reasoning as VideoLikeEventConsumer's unlike floor.
     */
    @Test
    void onMessage_deleted_withNoCommentsCounted_leavesTheCountAtZero() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        consumer.onMessage(objectMapper.writeValueAsString(
                CommentDeletedEvent.of(1L, Long.valueOf(video.getId()), 1L)), DELETED_HEADER);

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getCommentCount()).isZero();
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
