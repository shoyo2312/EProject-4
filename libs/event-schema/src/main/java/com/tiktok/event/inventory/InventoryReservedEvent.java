package com.tiktok.event.inventory;

import com.tiktok.event.DomainEvent;
import com.tiktok.event.order.OrderItem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryReservedEvent(
        String eventId,
        Instant occurredAt,
        Long orderId,
        List<OrderItem> items
) implements DomainEvent {

    public static InventoryReservedEvent of(Long orderId, List<OrderItem> items) {
        return new InventoryReservedEvent(UUID.randomUUID().toString(), Instant.now(), orderId, items);
    }
}
