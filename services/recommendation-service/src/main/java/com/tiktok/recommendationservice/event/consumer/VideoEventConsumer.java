package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
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
 * video.video-events carries a publication and a deletion, both flat JSON objects with no type
 * field, so routing is on the eventType header video-service sets — see its VideoEventPublisher.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventConsumer {

    private static final String VIDEO_PUBLISHED = "VideoPublishedEvent";
    private static final String VIDEO_DELETED = "VideoDeletedEvent";

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        // Absent header means a producer older than the deletion event, and everything that topic
        // carried then was a publication. Nothing else writes to it, so this is not a guess.
        String eventType = eventTypeHeader == null
                ? VIDEO_PUBLISHED
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (VIDEO_PUBLISHED.equals(eventType)) {
            VideoPublishedEvent event = objectMapper.readValue(payload, VideoPublishedEvent.class);
            inboxService.runOnce(event.eventId(), () ->
                    recommendationService.recordVideoPublished(event.videoId(), event.tags()));
        } else if (VIDEO_DELETED.equals(eventType)) {
            VideoDeletedEvent event = objectMapper.readValue(payload, VideoDeletedEvent.class);
            inboxService.runOnce(event.eventId(), () ->
                    recommendationService.recordVideoDeleted(event.videoId()));
        } else {
            log.debug("Ignoring video eventType={}", eventType);
        }
    }
}
