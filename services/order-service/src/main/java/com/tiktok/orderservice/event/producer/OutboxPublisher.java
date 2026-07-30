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
            kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
            event.markPublished();
        }
    }
}
