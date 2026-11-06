package com.tiktok.analyticsservice.event.consumer;

import com.tiktok.analyticsservice.config.KafkaBatchConfig;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.VideoSavedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** Saves, counted net like likes: a SAVED row and an UNSAVED row per toggle. */
@Component
@RequiredArgsConstructor
public class VideoSavedEventConsumer {

    private final EngagementEventRepository engagementEventRepository;
    private final ObjectMapper objectMapper;

    /** A whole poll at a time, written as one INSERT — see KafkaBatchConfig. */
    @KafkaListener(topics = "interaction.save-events", groupId = "analytics-service",
            containerFactory = KafkaBatchConfig.BATCH_FACTORY)
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        BatchWriter.write(records, record -> {
            VideoSavedEvent event = objectMapper.readValue(record.value(), VideoSavedEvent.class);
            String eventType = event.saved() ? "SAVED" : "UNSAVED";
            return new EngagementEventRepository.Row(event.eventId(), eventType, String.valueOf(event.videoId()),
                    event.userId(), event.occurredAt());
        }, engagementEventRepository::insertAll);
    }
}
