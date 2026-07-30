package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CommentCreatedEventConsumer {

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.comment-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload) {
        CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);

        if (!inboxService.markIfNew(event.eventId())) {
            return;
        }

        recommendationService.recordComment(String.valueOf(event.videoId()));
    }
}
