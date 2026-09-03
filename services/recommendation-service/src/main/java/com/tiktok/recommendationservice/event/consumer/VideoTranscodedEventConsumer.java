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
 * The moment a video becomes playable, which is the moment it may enter the feed. The publication
 * event cannot say this: it fires while the video is still PROCESSING because media-worker needs
 * it to start transcoding, so indexing on it offered viewers ids that hydration then dropped,
 * and suppressed them for the served-set's half hour just as they became playable.
 *
 * <p>A failed transcode is treated as a removal. Without it a video whose transcode failed for
 * good sat in trending and in the tag indexes until its owner happened to delete it — and there
 * is nothing else coming: media-worker does not retry a permanent failure.
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

        inboxService.runOnce(event.eventId(), () -> {
            if (event.success()) {
                recommendationService.recordVideoReady(event.videoId());
            } else {
                log.warn("Transcode failed for videoId={}, keeping it out of the feed: {}",
                        event.videoId(), event.failureReason());
                recommendationService.recordVideoDeleted(event.videoId());
            }
        });
    }
}
