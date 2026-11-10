package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Last week's time on the app, for the account's weekly screen time update. user-service sends one
 * per account and week, on Monday from 09:00 in the account's zone, when the switch is on and the
 * week has any time recorded. notification-service turns it into an inbox entry; it never pushes.
 *
 * <p>On {@code user.screen-time-summary-events}, keyed by {@code userId}. The consumer claims
 * {@code eventId}: an outbox re-send must not write the entry twice.
 *
 * @param weekStart    the week's Monday, {@code YYYY-MM-DD}, in the account's zone
 * @param totalSeconds day and night time together, always more than zero
 */
public record WeeklyScreenTimeSummaryEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String weekStart,
        Long totalSeconds
) implements DomainEvent {

    public static WeeklyScreenTimeSummaryEvent of(Long userId, String weekStart, long totalSeconds) {
        return new WeeklyScreenTimeSummaryEvent(UUID.randomUUID().toString(), Instant.now(), userId, weekStart, totalSeconds);
    }
}
