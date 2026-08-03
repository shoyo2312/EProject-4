package com.tiktok.inventoryservice.event.producer;

import com.tiktok.event.inventory.InventoryReleasedEvent;
import com.tiktok.event.inventory.InventoryReservationFailedEvent;
import com.tiktok.event.inventory.InventoryReservedEvent;
import com.tiktok.inventoryservice.entity.OutboxEvent;
import com.tiktok.inventoryservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Polls unpublished outbox rows and forwards them to Kafka, one topic per event type so
 * downstream consumers (order-service, eventually) can subscribe to just the outcome
 * they care about instead of branching on a shared inventory topic.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            InventoryReservedEvent.class.getSimpleName(), "inventory.reserved-events",
            InventoryReservationFailedEvent.class.getSimpleName(), "inventory.reservation-failed-events",
            InventoryReleasedEvent.class.getSimpleName(), "inventory.released-events"
    );

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            String topic = TOPIC_BY_EVENT_TYPE.get(event.getEventType());
            // TODO(outbox): send() is async, so a record the broker rejects is marked published
            // anyway and never retried. A dropped InventoryReserved/ReleasedEvent leaves stock
            // held by an order that will never complete. Migrate to kafka-lib's OutboxDispatcher,
            // which marks only after the ack. See docs/outbox-migration.md — note this service
            // has @KafkaListeners, so the dependency also switches them to bounded retry + DLQ.
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
            event.markPublished();
        }
    }
}
