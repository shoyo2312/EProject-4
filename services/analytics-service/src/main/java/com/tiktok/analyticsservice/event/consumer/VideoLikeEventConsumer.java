package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.config.KafkaBatchConfig;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.VideoLikeEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class VideoLikeEventConsumer {

    private final EngagementEventRepository engagementEventRepository;
    private final ObjectMapper objectMapper;

    /** A whole poll at a time, written as one INSERT — see KafkaBatchConfig. */
    @KafkaListener(topics = "interaction.like-events", groupId = "analytics-service",
            containerFactory = KafkaBatchConfig.BATCH_FACTORY)
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        BatchWriter.write(records, record -> {
            VideoLikeEvent event = objectMapper.readValue(record.value(), VideoLikeEvent.class);
            String eventType = event.liked() ? "LIKED" : "UNLIKED";
            return new EngagementEventRepository.Row(event.eventId(), eventType, String.valueOf(event.videoId()),
                    event.userId(), event.occurredAt());
        }, engagementEventRepository::insertAll);
    }
}
