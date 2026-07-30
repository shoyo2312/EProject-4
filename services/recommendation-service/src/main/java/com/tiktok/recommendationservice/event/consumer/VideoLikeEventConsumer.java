package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoLikeEventConsumer {

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.like-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoLikeEvent event = objectMapper.readValue(payload, VideoLikeEvent.class);

        if (!inboxService.markIfNew(event.eventId())) {
            return;
        }

        recommendationService.recordLike(String.valueOf(event.videoId()), event.liked());
    }
}
