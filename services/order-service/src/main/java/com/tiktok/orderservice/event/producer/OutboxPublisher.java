package com.tiktok.orderservice.event.producer;

import com.tiktok.event.order.OrderCancelledEvent;
import com.tiktok.event.order.OrderConfirmedEvent;
import com.tiktok.event.order.OrderCreatedEvent;
import com.tiktok.orderservice.entity.OutboxEvent;
import com.tiktok.orderservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Polls unpublished outbox rows and forwards them to Kafka, one topic per event type.
 * Topic names here are a contract inventory-service's consumers already expect
 * (order.created-events, order.cancelled-events, order.confirmed-events) -- keep them in
 * sync if either side changes.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            OrderCreatedEvent.class.getSimpleName(), "order.created-events",
            OrderCancelledEvent.class.getSimpleName(), "order.cancelled-events",
            OrderConfirmedEvent.class.getSimpleName(), "order.confirmed-events"
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
            // anyway and never retried. A dropped OrderCreatedEvent strands the saga: the order
            // sits PENDING and inventory is never reserved or released. Migrate to kafka-lib's
            // OutboxDispatcher, which marks only after the ack. See docs/outbox-migration.md —
            // note this service has @KafkaListeners, so the dependency also switches them to
            // bounded retry + DLQ.
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
            event.markPublished();
        }
    }
}
