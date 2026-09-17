package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.searchservice.index.SearchIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The only thing that makes a video searchable.
 *
 * <p>The same verdict video-service turns into a status, applied to the search projection so the
 * two agree. Without this listener the index had no idea moderation existed: a transcode marked
 * the document PUBLISHED and nothing ever revisited it, so an unscreened video was searchable
 * immediately and a rejected one stayed searchable permanently.
 *
 * <p>Only APPROVED publishes. REVIEW and REJECTED both land on statuses the search query does not
 * match, which is a whitelist — the same shape video-service's read paths use, so a verdict this
 * build does not recognise is invisible rather than published by default.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoModerationEventConsumer {

    private final SearchIndexWriter searchIndexWriter;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-moderation-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoModerationCompletedEvent event =
                objectMapper.readValue(payload, VideoModerationCompletedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                searchIndexWriter.applyOutcome(event.videoId(), statusOf(event), null, null));
    }

    /** Mirrors {@code Video.applyModeration} in video-service; the two must not drift. */
    private static String statusOf(VideoModerationCompletedEvent event) {
        if (event.verdict() == null) {
            log.warn("Moderation verdict missing for videoId={}, keeping it out of search", event.videoId());
            return "PENDING_REVIEW";
        }
        return switch (event.verdict()) {
            case APPROVED -> "PUBLISHED";
            case REVIEW -> "PENDING_REVIEW";
            case REJECTED -> "REJECTED";
        };
    }
}
