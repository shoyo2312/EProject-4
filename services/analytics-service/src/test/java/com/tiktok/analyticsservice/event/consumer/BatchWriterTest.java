package com.tiktok.analyticsservice.event.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.BatchListenerFailedException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchWriterTest {

    private final List<List<String>> inserts = new ArrayList<>();

    private static ConsumerRecord<String, String> record(long offset, String value) {
        return new ConsumerRecord<>("interaction.like-events", 0, offset, "k", value);
    }

    private static String parse(ConsumerRecord<String, String> record) {
        if (record.value().startsWith("{")) {
            throw new IllegalArgumentException("unreadable");
        }
        return record.value();
    }

    @Test
    void aPollIsOneInsert() {
        BatchWriter.write(List.of(record(0, "a"), record(1, "b"), record(2, "c")), BatchWriterTest::parse, inserts::add);

        assertThat(inserts).containsExactly(List.of("a", "b", "c"));
    }

    /**
     * The rows before the bad record are written and the failure names that record, so the error
     * handler commits up to it and dead-letters it alone. Nothing after it is written here: those
     * records are delivered again.
     */
    @Test
    void anUnreadableRecordWritesWhatCameBeforeItAndIsNamedAlone() {
        ConsumerRecord<String, String> bad = record(1, "{not an event");

        assertThatThrownBy(() -> BatchWriter.write(List.of(record(0, "a"), bad, record(2, "c")),
                BatchWriterTest::parse, inserts::add))
                .isInstanceOfSatisfying(BatchListenerFailedException.class,
                        e -> assertThat(e.getRecord()).isSameAs(bad));

        assertThat(inserts).containsExactly(List.of("a"));
    }

    @Test
    void anUnreadableFirstRecordWritesNothing() {
        assertThatThrownBy(() -> BatchWriter.write(List.of(record(0, "{"), record(1, "b")),
                BatchWriterTest::parse, inserts::add))
                .isInstanceOf(BatchListenerFailedException.class);

        assertThat(inserts).isEmpty();
    }
}
