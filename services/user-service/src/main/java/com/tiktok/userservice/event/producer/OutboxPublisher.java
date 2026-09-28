package com.tiktok.userservice.event.producer;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drains the outbox to Kafka, one topic per aggregate type. Marking is delegated to
 * {@link OutboxDispatcher} so a row is only marked published once the broker acknowledges it — see
 * that class for why doing it inline loses events.
 *
 * <p>Each topic carries one event type, so no eventType header: no consumer has anything to route.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Map<String, String> TOPICS = Map.of(
            UserEventPublisher.FOLLOW_AGGREGATE, "user.follow-events",
            UserEventPublisher.FOLLOW_REQUEST_AGGREGATE, "user.follow-request-events",
            UserEventPublisher.PRIVACY_AGGREGATE, "user.privacy-events");

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
        int published = outboxDispatcher.dispatch(pending, this::toRecord, event -> acked.add(event.getId()));
        if (!acked.isEmpty()) {
            outboxEventRepository.markPublished(acked, Instant.now());
        }

        if (published < pending.size()) {
            log.warn("Published {}/{} user outbox events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        String topic = TOPICS.get(event.getAggregateType());
        if (topic == null) {
            // Thrown, not guessed: the dispatcher leaves the row pending for an instance that knows
            // the type — the normal state of affairs halfway through a rolling deploy. The reverse
            // is not covered: an instance from before this map sends every row to
            // user.follow-events and marks it published, so the deploy that brings the map in has
            // to stop every old replica before starting a new one.
            throw new IllegalStateException("No topic for outbox aggregate type " + event.getAggregateType());
        }
        return new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload());
    }
}
