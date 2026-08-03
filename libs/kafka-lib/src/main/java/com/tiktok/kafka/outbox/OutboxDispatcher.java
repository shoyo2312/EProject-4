package com.tiktok.kafka.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The outbox pattern only guarantees at-least-once delivery if a row is marked published
 * <em>after</em> the broker acknowledges it. {@code KafkaTemplate.send} is asynchronous: it
 * returns a future and throws synchronously only for serialization or buffer-exhaustion
 * errors. Marking right after the call therefore marks rows the broker never received, and
 * because the poll query only picks up unpublished rows those events are never retried —
 * silently lost, which is the exact failure the outbox exists to prevent.
 *
 * <p>Each service still owns its own storage, topic and record shape; this only owns the
 * send/ack/mark ordering that all of them have to get right.
 */
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final KafkaOperations<String, String> kafkaOperations;
    private final Duration ackTimeout;

    public OutboxDispatcher(KafkaOperations<String, String> kafkaOperations, Duration ackTimeout) {
        this.kafkaOperations = kafkaOperations;
        this.ackTimeout = ackTimeout;
    }

    /**
     * Sends the whole batch before waiting on any of it, so records pipeline through the
     * producer instead of paying a broker round-trip each. {@code onPublished} runs only for
     * records the broker acknowledged; everything else stays unpublished and is picked up by
     * the next poll, at the cost of a possible duplicate — consumers here are idempotent.
     *
     * @return how many records were acknowledged
     */
    public <T> int dispatch(List<T> batch,
                            Function<T, ProducerRecord<String, String>> toRecord,
                            Consumer<T> onPublished) {
        List<T> sent = new ArrayList<>(batch.size());
        List<ProducerRecord<String, String>> sentRecords = new ArrayList<>(batch.size());
        List<CompletableFuture<?>> acks = new ArrayList<>(batch.size());

        for (T item : batch) {
            ProducerRecord<String, String> record = null;
            try {
                record = toRecord.apply(item);
                acks.add(kafkaOperations.send(record));
                sent.add(item);
                sentRecords.add(record);
            } catch (RuntimeException ex) {
                // Building or enqueuing this record failed outright. Leave it unpublished and
                // keep going: one unserializable row must not block the rest of the batch.
                log.error("Could not enqueue outbox record key={}, leaving it for the next poll",
                        record == null ? "<unbuilt>" : record.key(), ex);
            }
        }

        int published = 0;
        for (int i = 0; i < sent.size(); i++) {
            ProducerRecord<String, String> record = sentRecords.get(i);
            try {
                acks.get(i).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
                onPublished.accept(sent.get(i));
                published++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted waiting for outbox acks; {} record(s) left unpublished",
                        sent.size() - i);
                break;
            } catch (ExecutionException ex) {
                log.error("Broker rejected topic={} key={}, will retry next poll",
                        record.topic(), record.key(), ex.getCause());
            } catch (TimeoutException ex) {
                log.error("No acknowledgement for topic={} key={} within {}, will retry next poll",
                        record.topic(), record.key(), ackTimeout);
            }
        }
        return published;
    }
}
