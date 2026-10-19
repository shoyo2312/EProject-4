package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * admin-service's sweep closed a batch of reports nobody got to. One per batch rather than a
 * {@link ReportResolvedEvent} per target: a run can close thousands of reports across as many
 * targets, without settling everything pending on any of them, and the only reader — the admin
 * console, through chat-service — re-reads its lists whatever the payload says.
 */
public record StaleReportsDismissedEvent(
        String eventId,
        Instant occurredAt,
        int dismissed
) implements DomainEvent {

    public static StaleReportsDismissedEvent of(int dismissed) {
        return new StaleReportsDismissedEvent(UUID.randomUUID().toString(), Instant.now(), dismissed);
    }
}
