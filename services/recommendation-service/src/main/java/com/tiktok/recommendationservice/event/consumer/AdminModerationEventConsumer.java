package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * An admin takedown hid the video in video-service and search, but the feed kept handing out its
 * id from trending and the tag indexes. admin.moderation-events is shared with user bans and
 * comment removals, so routing is on the eventType header only, never on payload shape.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String VIDEO_TAKEN_DOWN = "VideoTakenDownEvent";
    private static final String VIDEO_RESTORED = "VideoRestoredEvent";

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        if (eventTypeHeader == null) {
            // A retry cannot add a header the producer did not send, so warn rather than throw.
            log.warn("Moderation event without an eventType header, dropped: {}", payload);
            return;
        }
        String eventType = new String(eventTypeHeader, StandardCharsets.UTF_8);

        switch (eventType) {
            case VIDEO_TAKEN_DOWN -> {
                VideoTakenDownEvent event = objectMapper.readValue(payload, VideoTakenDownEvent.class);
                inboxService.runOnce(event.eventId(), () -> recommendationService.recordTakenDown(event.videoId()));
            }
            case VIDEO_RESTORED -> {
                VideoRestoredEvent event = objectMapper.readValue(payload, VideoRestoredEvent.class);
                inboxService.runOnce(event.eventId(), () -> recommendationService.recordRestored(event.videoId()));
            }
            default -> log.debug("Ignoring moderation eventType={}", eventType);
        }
    }
}
