package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.searchservice.index.SearchIndexWriter;
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

    private final SearchIndexWriter searchIndexWriter;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "search-service")
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
            idempotentEventProcessor.runOnce(event.eventId(), VIDEO_PUBLISHED, () ->
                    searchIndexWriter.indexPublication(event.videoId(), event.userId(), event.title(),
                            event.description(), event.tags(), event.occurredAt()));
        } else if (VIDEO_DELETED.equals(eventType)) {
            VideoDeletedEvent event = objectMapper.readValue(payload, VideoDeletedEvent.class);
            // The document goes, rather than gaining a deleted flag: search has no use for a video
            // nobody can open, and every query would then have to remember to exclude it. Deleting
            // an id that is not there is a no-op in Elasticsearch, which is what a VideoDeletedEvent
            // for a video whose publication never went out looks like from here.
            idempotentEventProcessor.runOnce(event.eventId(), VIDEO_DELETED, () ->
                    searchIndexWriter.deleteVideo(event.videoId()));
        } else {
            log.debug("Ignoring video eventType={}", eventType);
        }
    }
}
