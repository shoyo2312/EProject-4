package com.tiktok.userservice.event.producer;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Drains the outbox to user.follow-events. Marking is delegated to {@link OutboxDispatcher} so a
 * row is only marked published once the broker acknowledges it — see that class for why doing it
 * inline loses events.
 *
 * <p>The topic carries one event type, so no eventType header: the consumer has nothing to route.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String TOPIC = "user.follow-events";

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(pending, this::toRecord, OutboxEvent::markPublished);

        if (published < pending.size()) {
            log.warn("Published {}/{} user outbox events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        return new ProducerRecord<>(TOPIC, event.getAggregateId(), event.getPayload());
    }
}
