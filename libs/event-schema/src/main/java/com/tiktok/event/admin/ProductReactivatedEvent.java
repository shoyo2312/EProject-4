package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record ProductReactivatedEvent(
        String eventId,
        Instant occurredAt,
        Long productId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static ProductReactivatedEvent of(Long productId, Long adminId, String reason) {
        return new ProductReactivatedEvent(UUID.randomUUID().toString(), Instant.now(), productId, adminId, reason);
    }
}
