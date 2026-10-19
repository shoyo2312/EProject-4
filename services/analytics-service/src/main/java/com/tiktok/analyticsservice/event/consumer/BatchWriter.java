package com.tiktok.analyticsservice.event.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.BatchListenerFailedException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Turns one poll into one INSERT, without letting one unreadable record take the rest of the poll
 * down with it.
 *
 * <p>A record that does not parse stops the batch where it is: the rows before it are written,
 * and the failure names that record, so kafka-lib's error handler commits everything before it
 * and sends it alone to the dead-letter topic, as it did when these listeners took one record at
 * a time. The records after it are delivered again. A failure to write is not narrowed down like
 * that — every row in the batch is equally affected — so it fails the batch as a whole and the
 * handler retries all of it. Either way a row may be written twice, which ReplacingMergeTree
 * absorbs.
 */
final class BatchWriter {

    @FunctionalInterface
    interface Parser<T> {
        T parse(ConsumerRecord<String, String> record) throws Exception;
    }

    private BatchWriter() {
    }

    static <T> void write(List<ConsumerRecord<String, String>> records, Parser<T> parser, Consumer<List<T>> insert) {
        List<T> rows = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            T row;
            try {
                row = parser.parse(record);
            } catch (Exception e) {
                if (!rows.isEmpty()) {
                    insert.accept(rows);
                }
                throw new BatchListenerFailedException("Unreadable record on " + record.topic(), e, record);
            }
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            insert.accept(rows);
        }
    }
}
