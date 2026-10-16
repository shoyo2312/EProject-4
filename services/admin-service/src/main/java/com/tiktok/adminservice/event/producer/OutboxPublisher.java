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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Report events go to admin.report-events, everything else to admin.moderation-events. Both mix
 * several event types with no type field in the JSON payload itself, so the eventType header is
 * the only way a consumer can tell them apart.
 *
 * <p>Marking is delegated to {@link OutboxDispatcher} so a row is only marked published once
 * the broker acknowledges it — see that class for why doing it inline loses events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final String MODERATION_TOPIC = "admin.moderation-events";
    private static final String REPORT_TOPIC = "admin.report-events";
    private static final String EVENT_TYPE_HEADER = "eventType";

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    /**
     * Not {@code @Transactional}: the dispatcher waits up to the ack timeout for the broker, and a
     * transaction around that wait held a pooled connection for all of it whenever Kafka was slow
     * or down. The acknowledged rows are marked afterwards in one short update of their own.
     */
    @Scheduled(fixedDelayString = "${admin.outbox.poll-millis}")
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
            log.warn("Published {}/{} admin outbox events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topicFor(event), event.getAggregateId(), event.getPayload());
        record.headers().add(new RecordHeader(
                EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    static String topicFor(OutboxEvent event) {
        return AdminEventProducer.REPORT_AGGREGATE.equals(event.getAggregateType()) ? REPORT_TOPIC : MODERATION_TOPIC;
    }
}
