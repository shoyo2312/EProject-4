package com.tiktok.adminservice.event.producer;

import com.tiktok.adminservice.entity.OutboxEvent;
import com.tiktok.adminservice.repository.OutboxEventRepository;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * admin.moderation-events mixes several event types (UserBanned, VideoTakenDown, ...) with no
 * type field in the JSON payload itself, so the eventType header is the only way a consumer can
 * tell them apart.
 *
 * <p>Marking is delegated to {@link OutboxDispatcher} so a row is only marked published once
 * the broker acknowledges it — see that class for why doing it inline loses events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String TOPIC = "admin.moderation-events";
    private static final String EVENT_TYPE_HEADER = "eventType";

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
            log.warn("Published {}/{} admin outbox events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                TOPIC, event.getAggregateId(), event.getPayload());
        record.headers().add(new RecordHeader(
                EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8)));
        return record;
    }
}
