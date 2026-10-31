package com.tiktok.kafka.dlt;

import java.time.Instant;

/**
 * One record on a {@code <topic>.DLT}, as its headers describe it. The value is left out on
 * purpose: it is an event payload — a comment, a caption, an account's details — and what a
 * reader needs to decide on a replay is which event failed where and why, not its content.
 *
 * @param parkedAt        when the error handler gave up on it
 * @param key             the original record's key, as UTF-8
 * @param eventType       the {@code eventType} header, or null for a producer that sends none
 * @param origin          where it failed, {@code topic-partition@offset}; null for a record kafka-lib did not park
 * @param consumerGroup   the group whose listener failed on it
 * @param error           the deepest cause in the stack trace, one line
 * @param pending         past the {@code dlt-replay} group's position, so a replay of every
 *                        pending record would still reach it
 */
public record DeadLetter(String topic, int partition, long offset, Instant parkedAt, String key, String eventType,
                         String origin, String consumerGroup, String error, boolean pending) {
}
