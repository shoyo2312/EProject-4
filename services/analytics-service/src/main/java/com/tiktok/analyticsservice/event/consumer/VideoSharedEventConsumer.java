package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.VideoSharedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoSharedEventConsumer {

    private final EngagementEventRepository engagementEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.share-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoSharedEvent event = objectMapper.readValue(payload, VideoSharedEvent.class);
        engagementEventRepository.insert(event.eventId(), "SHARED", String.valueOf(event.videoId()), event.userId(), event.occurredAt());
    }
}
