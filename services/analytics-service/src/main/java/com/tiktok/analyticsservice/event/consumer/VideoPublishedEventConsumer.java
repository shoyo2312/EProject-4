package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.video.VideoPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VideoPublishedEventConsumer {

    private final EngagementEventRepository engagementEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoPublishedEvent event = objectMapper.readValue(payload, VideoPublishedEvent.class);
        engagementEventRepository.insert(event.eventId(), "PUBLISHED", event.videoId(), event.userId(), event.occurredAt());
    }
}
