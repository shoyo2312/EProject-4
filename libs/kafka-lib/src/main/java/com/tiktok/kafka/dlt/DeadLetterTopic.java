package com.tiktok.kafka.dlt;

import java.time.Instant;

/**
 * @param retained  records the broker still holds; retention, not a replay, is what takes one away
 * @param pending   of those, the ones past the {@code dlt-replay} group's position
 * @param latestAt  when the newest of them was parked; null when there is none
 */
public record DeadLetterTopic(String name, String originTopic, long retained, long pending, Instant latestAt) {
}
