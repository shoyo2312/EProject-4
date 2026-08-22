package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoSharedEventConsumer {

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.share-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoSharedEvent event = objectMapper.readValue(payload, VideoSharedEvent.class);

        inboxService.runOnce(event.eventId(), () ->
                recommendationService.recordShare(String.valueOf(event.videoId())));
    }
}
