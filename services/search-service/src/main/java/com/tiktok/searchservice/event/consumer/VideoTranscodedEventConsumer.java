package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.searchservice.index.SearchIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A finished transcode is not a published video. video-service's {@code Video.markTranscoded}
 * parks it at PENDING_MODERATION and only a moderation verdict moves it on — see
 * {@link VideoModerationEventConsumer}, which is what eventually makes a video searchable.
 *
 * <p>This used to index a success as PUBLISHED, which is the status the search query filters on:
 * every upload was findable by title, description and tag before anything had screened it, and a
 * video the classifier went on to reject stayed findable for good, since nothing else on this
 * side ever touched its status again.
 */
@Component
@RequiredArgsConstructor
public class VideoTranscodedEventConsumer {

    private final SearchIndexWriter searchIndexWriter;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-transcoded-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoTranscodedEvent event = objectMapper.readValue(payload, VideoTranscodedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                searchIndexWriter.applyOutcome(
                        event.videoId(),
                        event.success() ? "PENDING_MODERATION" : "FAILED",
                        event.thumbnailUrl(),
                        event.durationSeconds()));
    }
}
