package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record ProductSuspendedEvent(
        String eventId,
        Instant occurredAt,
        Long productId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static ProductSuspendedEvent of(Long productId, Long adminId, String reason) {
        return new ProductSuspendedEvent(UUID.randomUUID().toString(), Instant.now(), productId, adminId, reason);
    }
}
