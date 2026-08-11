package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * admin.moderation-events carries several unrelated event types (UserBanned, ProductSuspended,
 * ...) with identical-looking JSON shapes for the video ones, so the eventType Kafka header
 * (set by admin-service's OutboxPublisher) is what routing relies on instead of the payload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String VIDEO_TAKEN_DOWN = "VideoTakenDownEvent";
    private static final String VIDEO_RESTORED = "VideoRestoredEvent";

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader);

        if (VIDEO_TAKEN_DOWN.equals(eventType)) {
            handleTakenDown(objectMapper.readValue(payload, VideoTakenDownEvent.class));
        } else if (VIDEO_RESTORED.equals(eventType)) {
            handleRestored(objectMapper.readValue(payload, VideoRestoredEvent.class));
        }
        // Any other eventType (UserBanned, ProductSuspended, ...) is not relevant to video-service.
    }

    /**
     * Soft-deleted videos are skipped, same as in {@link VideoTranscodedEventConsumer}: an owner
     * can delete between the moderator's click and this event arriving, and a restore landing
     * afterwards would leave a document that is both deleted and PUBLISHED. Nothing displays it,
     * so nothing corrects it either.
     */
    private void handleTakenDown(VideoTakenDownEvent event) {
        idempotentEventProcessor.runOnce(event.eventId(), VIDEO_TAKEN_DOWN, () ->
                videoRepository.findByIdAndDeletedAtIsNull(event.videoId()).ifPresentOrElse(
                        video -> {
                            video.markTakenDown();
                            videoRepository.updateModerationStatus(video);
                        },
                        () -> log.warn("VideoTakenDownEvent for unknown or deleted videoId={}", event.videoId())));
    }

    private void handleRestored(VideoRestoredEvent event) {
        idempotentEventProcessor.runOnce(event.eventId(), VIDEO_RESTORED, () ->
                videoRepository.findByIdAndDeletedAtIsNull(event.videoId()).ifPresentOrElse(
                        video -> {
                            video.markRestored();
                            videoRepository.updateModerationStatus(video);
                        },
                        () -> log.warn("VideoRestoredEvent for unknown or deleted videoId={}", event.videoId())));
    }
}
