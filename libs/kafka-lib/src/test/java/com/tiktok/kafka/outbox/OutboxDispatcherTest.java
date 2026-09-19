package com.tiktok.kafka.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The contract every outbox in the monorepo relies on: a row is marked only after the broker acked it. */
class OutboxDispatcherTest {

    @SuppressWarnings("unchecked")
    private final KafkaOperations<String, String> kafka = mock(KafkaOperations.class);
    private final OutboxDispatcher dispatcher = new OutboxDispatcher(kafka, Duration.ofMillis(200));
    private final List<String> marked = new ArrayList<>();

    @Test
    void onlyAcknowledgedRecords_areMarked() {
        ack("a");
        when(kafka.send(forKey("b"))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("rejected")));
        when(kafka.send(forKey("c"))).thenReturn(new CompletableFuture<>()); // never acked

        int published = dispatcher.dispatch(List.of("a", "b", "c"), this::record, marked::add);

        assertThat(published).isEqualTo(1);
        assertThat(marked).containsExactly("a");
    }

    /** One row that cannot even be enqueued must not hold back the rest of the batch. */
    @Test
    void aRecordThatCannotBeSent_doesNotBlockTheOthers() {
        when(kafka.send(forKey("a"))).thenThrow(new IllegalStateException("buffer full"));
        ack("b");

        dispatcher.dispatch(List.of("a", "b"), this::record, marked::add);

        assertThat(marked).containsExactly("b");
    }

    private void ack(String key) {
        when(kafka.send(forKey(key))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    private ProducerRecord<String, String> forKey(String key) {
        return argThat(record -> record != null && key.equals(record.key()));
    }

    private ProducerRecord<String, String> record(String key) {
        return new ProducerRecord<>("topic", key, "payload-" + key);
    }
}
