package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.VideoLikeEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoLikeEventConsumer {

    private final EngagementEventRepository engagementEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.like-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoLikeEvent event = objectMapper.readValue(payload, VideoLikeEvent.class);
        String eventType = event.liked() ? "LIKED" : "UNLIKED";
        engagementEventRepository.insert(event.eventId(), eventType, String.valueOf(event.videoId()), event.userId(), event.occurredAt());
    }
}
