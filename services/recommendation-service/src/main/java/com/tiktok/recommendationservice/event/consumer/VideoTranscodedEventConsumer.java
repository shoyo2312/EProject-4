package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Cleanup only. A transcode that failed is a video nothing will ever publish — media-worker does
 * not retry a permanent failure and no verdict is coming — so whatever the publication stashed
 * about it is taken back here rather than left to expire with its TTL.
 *
 * <p>A transcode that succeeded deliberately does nothing. It used to be what put the video in
 * front of viewers, but a playable video is not yet a screened one: video-service parks it at
 * PENDING_MODERATION, so indexing here offered ids that hydration then dropped and burned them in
 * the served-set's half-hour window as it did so — the same failure that moving off
 * VideoPublishedEvent was meant to end. Worse, a video the classifier went on to reject stayed in
 * trending and in the tag indexes with nothing left to remove it. {@link VideoModerationEventConsumer}
 * is where a video now enters the feed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTranscodedEventConsumer {

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-transcoded-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoTranscodedEvent event = objectMapper.readValue(payload, VideoTranscodedEvent.class);

        if (event.success()) {
            return;
        }

        inboxService.runOnce(event.eventId(), () -> {
            log.warn("Transcode failed for videoId={}, keeping it out of the feed: {}",
                    event.videoId(), event.failureReason());
            recommendationService.recordVideoDeleted(event.videoId());
        });
    }
}
