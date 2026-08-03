package com.tiktok.productservice.event.producer;

import com.tiktok.productservice.entity.OutboxEvent;
import com.tiktok.productservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
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

    private static final String TOPIC = "product.product-events";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            // TODO(outbox): send() is async, so a record the broker rejects is marked published
            // anyway and never retried — the event is lost. Migrate to kafka-lib's
            // OutboxDispatcher, which marks only after the ack. See docs/outbox-migration.md.
            kafkaTemplate.send(TOPIC, event.getAggregateId(), event.getPayload());
            event.markPublished();
        }
    }
}
