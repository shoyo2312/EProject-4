package com.tiktok.searchservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.searchservice.index.SearchIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoLikeEventConsumer {

    private final SearchIndexWriter searchIndexWriter;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.like-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoLikeEvent event = objectMapper.readValue(payload, VideoLikeEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                searchIndexWriter.applyCounterDelta(
                        String.valueOf(event.videoId()), "likeCount", event.liked() ? 1 : -1));
    }
}
