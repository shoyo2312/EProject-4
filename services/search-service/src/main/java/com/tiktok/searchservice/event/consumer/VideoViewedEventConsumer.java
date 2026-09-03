package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoViewedEvent;
import com.tiktok.searchservice.index.SearchIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * viewCount was indexed and returned in every search result but nothing ever wrote to it, so it
 * read zero for every video. interaction-service only emits this once a view has cleared its
 * deduplication window, so each one is a straight +1.
 */
@Component
@RequiredArgsConstructor
public class VideoViewedEventConsumer {

    private final SearchIndexWriter searchIndexWriter;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.view-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoViewedEvent event = objectMapper.readValue(payload, VideoViewedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                searchIndexWriter.applyCounterDelta(
                        String.valueOf(event.videoId()), "viewCount", 1));
    }
}
