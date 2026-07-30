package com.tiktok.paymentservice.event.producer;

import com.tiktok.event.payment.PaymentCompletedEvent;
import com.tiktok.event.payment.PaymentFailedEvent;
import com.tiktok.paymentservice.entity.OutboxEvent;
import com.tiktok.paymentservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Polls unpublished outbox rows and forwards them to Kafka. Topic names are a contract
 * order-service and inventory-service already consume (payment.completed-events,
 * payment.failed-events) -- keep them in sync if either side changes.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            PaymentCompletedEvent.class.getSimpleName(), "payment.completed-events",
            PaymentFailedEvent.class.getSimpleName(), "payment.failed-events"
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
