package com.tiktok.videoservice.event.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of());

        publisher.publishPending();

        verifyNoInteractions(kafkaOperations);
    }

    @Test
    void publishPending_sendsEventAndMarksPublished() {
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
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
        // tags ride on the event because recommendation-service has no read path into this
        // service's Mongo, and they are the only content feature it gets.
        assertThat(record.value()).contains(video.getId()).contains(video.getTitle()).contains("dance");

        assertThat(video.getEventPublishedAt()).isNotNull();
        verify(videoRepository).updateEventPublished(video);
    }

    @Test
    void publishPending_sendFailure_leavesVideoPendingForNextPoll() {
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
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
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
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

    /**
     * The duplicate this guards against is routine, not exotic: an ack that times out leaves the
     * row unmarked even though the broker took the record, and every replica polls. Consumers
     * deduplicate on eventId, so a fresh id on the retry is a second published video as far as
     * they can tell — recommendation-service scores it twice, analytics stores a second row.
     */
    @Test
    void publishPending_resendingTheSameVideo_reusesTheSameEventId() {
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("no ack in time"));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(failed, CompletableFuture.completedFuture(null));

        publisher.publishPending();
        publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(2)).send(captor.capture());

        assertThat(eventIdOf(captor.getAllValues().get(0)))
                .isEqualTo(eventIdOf(captor.getAllValues().get(1)));
    }

    /**
     * The topic now carries two event shapes with no type field between them, so the header is
     * the only thing a consumer can route on — and a deletion read as a publication indexes the
     * video it was meant to remove.
     */
    @Test
    void publishPending_labelsTheRecordWithItsEventType() {
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        assertThat(eventTypeOf(captureRecord())).isEqualTo("VideoPublishedEvent");
    }

    /**
     * The row is parked rather than left pending. The poll reads the oldest hundred unpublished
     * videos every five seconds, so a document that cannot be serialized comes back in the same
     * first position for ever and every video behind it waits on a retry that cannot succeed.
     */
    @Test
    void publishPending_unserializableVideo_isParkedInsteadOfBlockingThePoll() throws Exception {
        Video video = pendingVideo();
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));

        ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
        when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("no") {
        });
        VideoEventPublisher brokenPublisher = new VideoEventPublisher(
                videoRepository, new OutboxDispatcher(kafkaOperations, Duration.ofSeconds(5)), broken);

        brokenPublisher.publishPending();

        assertThat(video.getEventFailedAt()).isNotNull();
        verify(videoRepository).updateEventFailed(video);
        verify(videoRepository, never()).updateEventPublished(any(Video.class));
        verifyNoInteractions(kafkaOperations);
    }

    @Test
    void publishPendingDeletions_announcesTheRemovalAndMarksIt() {
        Video video = deletedVideo(true);
        when(videoRepository.findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc())
                .thenReturn(List.of(video));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingDeletions();

        ProducerRecord<String, String> record = captureRecord();
        assertThat(record.topic()).isEqualTo("video.video-events");
        // Same key as the publication, so Kafka orders the pair: no consumer can be handed the
        // removal of a video it has not been told about.
        assertThat(record.key()).isEqualTo(video.getId());
        assertThat(eventTypeOf(record)).isEqualTo("VideoDeletedEvent");
        // media-worker is the only party that can reclaim the source object, and this event is
        // the last thing that knows where it is.
        assertThat(record.value()).contains(video.getRawFileUrl());

        assertThat(video.getDeleteEventPublishedAt()).isNotNull();
        verify(videoRepository).updateDeleteEventPublished(video);
    }

    /**
     * publishPending skips soft-deleted rows, so a video deleted within five seconds of upload was
     * never announced to anyone — and its removal is announced anyway. The indexing consumers get
     * a no-op, but the raw upload is already in MinIO and this event carries the only key anything
     * still holds for it; staying quiet leaks the object with nothing left that could find it.
     */
    @Test
    void publishPendingDeletions_neverAnnouncedVideo_isStillAnnouncedSoTheUploadIsReclaimed() {
        Video video = deletedVideo(false);
        when(videoRepository.findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc())
                .thenReturn(List.of(video));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingDeletions();

        ProducerRecord<String, String> record = captureRecord();
        assertThat(eventTypeOf(record)).isEqualTo("VideoDeletedEvent");
        assertThat(record.value()).contains(video.getRawFileUrl());
        assertThat(video.getDeleteEventPublishedAt()).isNotNull();
        verify(videoRepository).updateDeleteEventPublished(video);
    }

    @Test
    void publishPendingDeletions_sendFailure_leavesTheDeletionPendingForNextPoll() {
        Video video = deletedVideo(true);
        when(videoRepository.findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc())
                .thenReturn(List.of(video));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(failed);

        publisher.publishPendingDeletions();

        assertThat(video.getDeleteEventPublishedAt()).isNull();
        verify(videoRepository, never()).updateDeleteEventPublished(any(Video.class));
    }

    private ProducerRecord<String, String> captureRecord() {
        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations).send(captor.capture());
        return captor.getValue();
    }

    private String eventTypeOf(ProducerRecord<String, String> record) {
        return new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8);
    }

    private Video deletedVideo(boolean alreadyAnnounced) {
        Video video = pendingVideo();
        if (alreadyAnnounced) {
            video.markEventPublished();
        }
        video.markDeleted();
        return video;
    }

    private String eventIdOf(ProducerRecord<String, String> record) {
        try {
            return objectMapper.readTree(record.value()).get("eventId").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Video pendingVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("My video")
                .rawFileUrl("s3://video-media/raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .tags(List.of("dance"))
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
