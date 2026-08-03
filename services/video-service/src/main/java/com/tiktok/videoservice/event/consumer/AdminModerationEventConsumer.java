package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.videoservice.entity.ProcessedEvent;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
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

    private final ProcessedEventRepository processedEventRepository;
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

    private void handleTakenDown(VideoTakenDownEvent event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        videoRepository.findById(event.videoId()).ifPresentOrElse(
                video -> {
                    video.markTakenDown();
                    videoRepository.save(video);
                },
                () -> log.warn("VideoTakenDownEvent for unknown videoId={}", event.videoId()));

        markProcessed(event.eventId(), VIDEO_TAKEN_DOWN);
    }

    private void handleRestored(VideoRestoredEvent event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        videoRepository.findById(event.videoId()).ifPresentOrElse(
                video -> {
                    video.markRestored();
                    videoRepository.save(video);
                },
                () -> log.warn("VideoRestoredEvent for unknown videoId={}", event.videoId()));

        markProcessed(event.eventId(), VIDEO_RESTORED);
    }

    private void markProcessed(String eventId, String eventType) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .eventType(eventType)
                .build());
    }
}
