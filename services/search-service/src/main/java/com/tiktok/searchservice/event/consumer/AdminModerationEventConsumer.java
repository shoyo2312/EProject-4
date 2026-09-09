package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.searchservice.index.SearchIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Moderation used to stop at video-service, which owns the record — nothing told the search
 * index. A taken-down video kept its PUBLISHED status here and went on being searchable.
 *
 * <p>admin.moderation-events carries several unrelated event types (UserBanned, ...) with no type
 * field in the payload, so routing is on the eventType header — see admin-service's
 * OutboxPublisher, and video-service's consumer of the same topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String VIDEO_TAKEN_DOWN = "VideoTakenDownEvent";
    private static final String VIDEO_RESTORED = "VideoRestoredEvent";

    private final SearchIndexWriter searchIndexWriter;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null
                ? null
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (eventType == null) {
            // Routing here is header-only: a takedown without one is dropped in silence and the
            // item stays searchable with nothing to say why. Warned rather than thrown, because a
            // retry cannot add a header that the producer did not send.
            log.warn("Moderation event without an eventType header, dropped: {}", payload);
            return;
        }

        switch (eventType) {
            case VIDEO_TAKEN_DOWN -> {
                VideoTakenDownEvent event = objectMapper.readValue(payload, VideoTakenDownEvent.class);
                idempotentEventProcessor.runOnce(event.eventId(), VIDEO_TAKEN_DOWN, () ->
                        searchIndexWriter.applyVideoStatus(event.videoId(), "TAKEN_DOWN"));
            }
            case VIDEO_RESTORED -> {
                VideoRestoredEvent event = objectMapper.readValue(payload, VideoRestoredEvent.class);
                idempotentEventProcessor.runOnce(event.eventId(), VIDEO_RESTORED, () ->
                        searchIndexWriter.restoreVideo(event.videoId()));
            }
            // UserBanned, ... — other services' events on a shared topic.
            default -> log.debug("Ignoring moderation eventType={}", eventType);
        }
    }
}
