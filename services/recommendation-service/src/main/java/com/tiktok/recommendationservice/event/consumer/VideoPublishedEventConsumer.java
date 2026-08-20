package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoPublishedEventConsumer {

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoPublishedEvent event = objectMapper.readValue(payload, VideoPublishedEvent.class);

        if (!inboxService.markIfNew(event.eventId())) {
            return;
        }

        recommendationService.recordVideoPublished(event.videoId(), event.tags());
    }
}
