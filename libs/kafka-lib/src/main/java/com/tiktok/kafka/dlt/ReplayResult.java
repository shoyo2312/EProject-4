package com.tiktok.kafka.dlt;

import java.time.Instant;

/**
 * What became of one DLT record.
 *
 * @param parkedAt the DLT record's timestamp, which tells it apart from a record at the same
 *                 offset after the topic was deleted and created again; null when there was none
 * @param detail   REPLAYED: where it went. HELD_BACK: what holds it. ALREADY_REPLAYED: where the
 *                 earlier replay is. Otherwise null
 */
public record ReplayResult(String topic, int partition, long offset, Instant parkedAt, Outcome outcome,
                           String detail) {

    public enum Outcome {
        REPLAYED,
        /** Not sent: the original partition has a later record under the same key, or may have. */
        HELD_BACK,
        ALREADY_REPLAYED,
        /** No {@code kafka_dlt-original-*} headers, so nothing says where it would go back to. */
        NOT_PARKED,
        NOT_FOUND
    }

    public String coordinates() {
        return topic + "-" + partition + "@" + offset;
    }
}
