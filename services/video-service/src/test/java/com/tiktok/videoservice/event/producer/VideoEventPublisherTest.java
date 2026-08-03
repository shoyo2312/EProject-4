package com.tiktok.videoservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito unit test (no Testcontainers) — this class has no Mongo-specific behavior
 * beyond calls the repository mock can stand in for.
 */
@ExtendWith(MockitoExtension.class)
class VideoEventPublisherTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private VideoEventPublisher publisher;

    @Test
    void publishPending_noPendingVideos_doesNotTouchKafka() {
        publisher = new VideoEventPublisher(videoRepository, kafkaTemplate, objectMapper);
        when(videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of());

        publisher.publishPending();

        verifyNoInteractionsWithKafka();
    }

    @Test
    void publishPending_sendsEventAndMarksPublished() {
        publisher = new VideoEventPublisher(videoRepository, kafkaTemplate, objectMapper);
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), payloadCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("video.video-events");
        assertThat(keyCaptor.getValue()).isEqualTo(video.getId());
        assertThat(payloadCaptor.getValue()).contains(video.getId()).contains(video.getTitle());

        assertThat(video.getEventPublishedAt()).isNotNull();
        verify(videoRepository).save(video);
    }

    @Test
    void publishPending_sendFailure_stillMarksPublished() {
        // Fire-and-forget by design (matches every other OutboxPublisher in this repo, see
        // VideoEventPublisher's class Javadoc): a broker-side failure is only logged, the
        // eventPublishedAt flag is set regardless and the event is not retried.
        publisher = new VideoEventPublisher(videoRepository, kafkaTemplate, objectMapper);
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        publisher.publishPending();

        assertThat(video.getEventPublishedAt()).isNotNull();
        verify(videoRepository).save(video);
    }

    private void verifyNoInteractionsWithKafka() {
        org.mockito.Mockito.verifyNoInteractions(kafkaTemplate);
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
