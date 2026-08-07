package com.tiktok.videoservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.VideoRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit test (no Testcontainers) — this class has no Mongo-specific behavior
 * beyond calls the repository mock can stand in for. Uses a real {@link OutboxDispatcher} over
 * a mocked template, since the mark-only-after-ack rule under test lives in the dispatcher.
 */
@ExtendWith(MockitoExtension.class)
class VideoEventPublisherTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private KafkaOperations<String, String> kafkaOperations;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private VideoEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new VideoEventPublisher(
                videoRepository,
                new OutboxDispatcher(kafkaOperations, Duration.ofSeconds(5)),
                objectMapper);
    }

    @Test
    void publishPending_noPendingVideos_doesNotTouchKafka() {
        when(videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of());

        publisher.publishPending();

        verifyNoInteractions(kafkaOperations);
    }

    @Test
    void publishPending_sendsEventAndMarksPublished() {
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations).send(captor.capture());

        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("video.video-events");
        assertThat(record.key()).isEqualTo(video.getId());
        assertThat(record.value()).contains(video.getId()).contains(video.getTitle());

        assertThat(video.getEventPublishedAt()).isNotNull();
        verify(videoRepository).updateEventPublished(video);
    }

    @Test
    void publishPending_sendFailure_leavesVideoPendingForNextPoll() {
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(failed);

        publisher.publishPending();

        assertThat(video.getEventPublishedAt())
                .as("an unacknowledged event must stay pending, otherwise it is lost for good")
                .isNull();
        verify(videoRepository, never()).updateEventPublished(any(Video.class));
    }

    @Test
    void publishPending_oneFailureDoesNotBlockTheRest() {
        Video failing = pendingVideo();
        Video succeeding = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(failing, succeeding));

        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(failed, CompletableFuture.completedFuture(null));

        publisher.publishPending();

        assertThat(failing.getEventPublishedAt()).isNull();
        assertThat(succeeding.getEventPublishedAt()).isNotNull();
        verify(videoRepository).updateEventPublished(succeeding);
    }

    private Video pendingVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("My video")
                .rawFileUrl("s3://raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
