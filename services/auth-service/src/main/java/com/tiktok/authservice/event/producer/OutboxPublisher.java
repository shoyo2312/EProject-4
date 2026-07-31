package com.tiktok.authservice.event.producer;

import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls unpublished outbox rows and forwards them to Kafka. Keeps producer writes
 * transactional with the DB change instead of dual-writing to Kafka + Postgres directly.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String TOPIC = "auth.user-events";
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload());
                event.markPublished();
            } catch (Exception ex) {
                log.error("Failed to publish outbox event id={} aggregateId={} eventType={}, will retry next poll",
                        event.getId(), event.getAggregateId(), event.getEventType(), ex);
                throw ex;
            }
        }
    }
}
