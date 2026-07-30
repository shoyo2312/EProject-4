package com.tiktok.event.inventory;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record InventoryReservationFailedEvent(
        String eventId,
        Instant occurredAt,
        Long orderId,
        String reason
) implements DomainEvent {

    public static InventoryReservationFailedEvent of(Long orderId, String reason) {
        return new InventoryReservationFailedEvent(UUID.randomUUID().toString(), Instant.now(), orderId, reason);
    }
}
