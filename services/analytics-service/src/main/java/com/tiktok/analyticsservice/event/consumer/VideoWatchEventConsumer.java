package com.tiktok.analyticsservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.config.KafkaBatchConfig;
import com.tiktok.analyticsservice.repository.TrainingDataRepository;
import com.tiktok.event.interaction.VideoWatchEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

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

    /** A whole poll at a time, written as one INSERT — see KafkaBatchConfig. */
    @KafkaListener(topics = "interaction.watch-events", groupId = "analytics-service",
            containerFactory = KafkaBatchConfig.BATCH_FACTORY)
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        BatchWriter.write(records, record -> {
            VideoWatchEvent event = objectMapper.readValue(record.value(), VideoWatchEvent.class);
            return new TrainingDataRepository.WatchRow(
                    event.eventId(),
                    String.valueOf(event.videoId()),
                    event.userId(),
                    event.watchedMs(),
                    event.durationMs(),
                    event.completed(),
                    event.occurredAt());
        }, trainingDataRepository::insertWatches);
    }
}
