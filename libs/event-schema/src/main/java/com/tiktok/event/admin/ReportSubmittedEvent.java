package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record ReportSubmittedEvent(
        String eventId,
        Instant occurredAt,
        Long reportId,
        String targetType,
        String targetId
) implements DomainEvent {

    public static ReportSubmittedEvent of(Long reportId, String targetType, String targetId) {
        return new ReportSubmittedEvent(UUID.randomUUID().toString(), Instant.now(), reportId, targetType, targetId);
    }
}
