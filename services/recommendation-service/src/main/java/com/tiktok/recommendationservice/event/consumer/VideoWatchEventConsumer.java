package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoWatchEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Watch sessions are the densest signal this service gets: a viewer likes a handful of videos a
 * day and watches hundreds. Unlike the view counter upstream, this stream is not deduplicated —
 * a rewatch is the strongest endorsement there is and must not be dropped.
 */
@Component
@RequiredArgsConstructor
public class VideoWatchEventConsumer {

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.watch-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoWatchEvent event = objectMapper.readValue(payload, VideoWatchEvent.class);

        if (!inboxService.markIfNew(event.eventId())) {
            return;
        }

        recommendationService.recordWatch(
                String.valueOf(event.videoId()),
                event.userId(),
                event.watchedMs(),
                event.durationMs(),
                event.completed());
    }
}
