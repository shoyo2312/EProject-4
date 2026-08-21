package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.TrainingDataRepository;
import com.tiktok.event.interaction.VideoWatchEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Stores every playback session as a training row. Unlike the recommendation-service consumer,
 * which folds a watch into a running score and therefore must not see the same event twice,
 * this one only appends: {@code ReplacingMergeTree} collapses a redelivered row on its next
 * merge, so a rebalance costs disk rather than correctness.
 */
@Component
@RequiredArgsConstructor
public class VideoWatchEventConsumer {

    private final TrainingDataRepository trainingDataRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.watch-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoWatchEvent event = objectMapper.readValue(payload, VideoWatchEvent.class);
        trainingDataRepository.insertWatch(
                event.eventId(),
                String.valueOf(event.videoId()),
                event.userId(),
                event.watchedMs(),
                event.durationMs(),
                event.completed(),
                event.occurredAt());
    }
}
