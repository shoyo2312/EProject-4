package com.tiktok.adminservice.dto.response;

import com.tiktok.kafka.dlt.ReplayResult;

import java.time.Instant;

/**
 * A DLT record as the console lists it — where and why it failed, never the payload: that is an
 * event's content, and deciding on a replay does not need it.
 *
 * @param origin     {@code topic-partition@offset} it failed at
 * @param error      the deepest cause of the failure, one line
 * @param pending    a replay of every pending record would still reach it
 * @param lastReplay the latest replay of it from the console; null when nobody has tried one
 */
public record DeadLetterRecordResponse(
        int partition,
        long offset,
        Instant parkedAt,
        String key,
        String eventType,
        String origin,
        String consumerGroup,
        String error,
        boolean pending,
        LastReplay lastReplay
) {

    /** @param detail where it went, what held it back, or where the earlier replay is */
    public record LastReplay(ReplayResult.Outcome outcome, String detail, boolean forced, Long adminId, Instant at) {
    }
}
