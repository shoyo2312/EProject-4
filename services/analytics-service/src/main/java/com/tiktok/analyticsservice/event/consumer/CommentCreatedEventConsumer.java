package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.CommentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CommentCreatedEventConsumer {

    private final EngagementEventRepository engagementEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.comment-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);
        engagementEventRepository.insert(event.eventId(), "COMMENTED", String.valueOf(event.videoId()), event.userId(), event.occurredAt());
    }
}
