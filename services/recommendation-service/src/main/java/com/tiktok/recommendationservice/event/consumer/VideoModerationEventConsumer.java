package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The moment a video may enter the feed, which is the moment it has been screened — not the
 * moment it became playable. Only APPROVED indexes; every other verdict removes, because a video
 * that is not PUBLISHED in video-service is dropped by the /videos/batch hydration the feed's ids
 * are redeemed through, and an id that hydrates to nothing still costs the viewer a slot and half
 * an hour of suppression in the served set.
 *
 * <p>Removal is unconditional rather than conditional on having indexed the video: it is the same
 * idempotent cleanup a deletion runs, so a REJECTED verdict for a video that never made it in is
 * a handful of Redis removes that match nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoModerationEventConsumer {

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-moderation-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoModerationCompletedEvent event =
                objectMapper.readValue(payload, VideoModerationCompletedEvent.class);

        inboxService.runOnce(event.eventId(), () -> {
            if (event.verdict() == ModerationVerdict.APPROVED) {
                recommendationService.recordVideoReady(event.videoId());
            } else {
                log.info("Verdict {} for videoId={}, keeping it out of the feed",
                        event.verdict(), event.videoId());
                recommendationService.recordVideoDeleted(event.videoId());
            }
        });
    }
}
