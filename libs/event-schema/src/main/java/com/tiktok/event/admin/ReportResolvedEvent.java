package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record ReportResolvedEvent(
        String eventId,
        Instant occurredAt,
        String targetType,
        String targetId
) implements DomainEvent {

    public static ReportResolvedEvent of(String targetType, String targetId) {
        return new ReportResolvedEvent(UUID.randomUUID().toString(), Instant.now(), targetType, targetId);
    }
}
