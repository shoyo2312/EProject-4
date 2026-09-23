package com.tiktok.authservice.event.producer;

import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Polls unpublished outbox rows and forwards them to Kafka. Keeps producer writes
 * transactional with the DB change instead of dual-writing to Kafka + Postgres directly.
 *
 * <p>Marking is delegated to {@link OutboxDispatcher} so a row is only marked published once
 * the broker acknowledges it — see that class for why doing it inline loses events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String TOPIC = "auth.user-events";

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    /**
     * Not {@code @Transactional}: the dispatcher waits up to the ack timeout for the broker, and a
     * transaction around that wait held a pooled connection for all of it whenever Kafka was slow
     * or down. The acknowledged rows are marked afterwards in one short update of their own.
     */
    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        List<Long> acked = new ArrayList<>(pending.size());
        int published = outboxDispatcher.dispatch(
                pending,
                event -> new ProducerRecord<>(TOPIC, event.getAggregateId(), event.getPayload()),
                event -> acked.add(event.getId()));
        if (!acked.isEmpty()) {
            outboxEventRepository.markPublished(acked, Instant.now());
        }

        if (published < pending.size()) {
            log.warn("Published {}/{} auth outbox events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }
}
