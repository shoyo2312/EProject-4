package com.tiktok.event.inventory;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record InventoryReleasedEvent(
        String eventId,
        Instant occurredAt,
        Long orderId,
        String reason
) implements DomainEvent {

    public static InventoryReleasedEvent of(Long orderId, String reason) {
        return new InventoryReleasedEvent(UUID.randomUUID().toString(), Instant.now(), orderId, reason);
    }
}
