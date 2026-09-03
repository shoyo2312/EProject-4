package com.tiktok.productservice.event.producer;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.productservice.entity.OutboxEvent;
import com.tiktok.productservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls unpublished outbox rows and forwards them to Kafka. Keeps producer writes
 * transactional with the DB change instead of dual-writing to Kafka + Postgres directly.
 *
 * <p>Marking is the dispatcher's job, not this loop's: {@code KafkaTemplate.send} is async and
 * throws synchronously only on a serialization error or a full buffer, so marking right after
 * the call marked rows the broker had rejected — and the poll query skips marked rows, so the
 * event was gone for good. See docs/outbox-migration.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String TOPIC = "product.product-events";

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(
                pending,
                event -> new ProducerRecord<>(TOPIC, event.getAggregateId(), event.getPayload()),
                OutboxEvent::markPublished);

        if (published < pending.size()) {
            log.warn("Published {}/{} outbox events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }
}
