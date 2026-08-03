package com.tiktok.adminservice.event.producer;

import com.tiktok.adminservice.entity.OutboxEvent;
import com.tiktok.adminservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * admin.moderation-events mixes several event types (UserBanned, VideoTakenDown, ...) with no
 * type field in the JSON payload itself, so the eventType header is the only way a consumer can
 * tell them apart.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String TOPIC = "admin.moderation-events";
    private static final String EVENT_TYPE_HEADER = "eventType";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    TOPIC, event.getAggregateId(), event.getPayload());
            record.headers().add(new RecordHeader(
                    EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8)));

            kafkaTemplate.send(record);
            event.markPublished();
        }
    }
}
