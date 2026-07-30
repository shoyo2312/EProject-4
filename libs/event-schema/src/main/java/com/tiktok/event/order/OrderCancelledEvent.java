package com.tiktok.event.order;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record OrderCancelledEvent(
        String eventId,
        Instant occurredAt,
        Long orderId,
        Long userId,
        String reason
) implements DomainEvent {

    public static OrderCancelledEvent of(Long orderId, Long userId, String reason) {
        return new OrderCancelledEvent(UUID.randomUUID().toString(), Instant.now(), orderId, userId, reason);
    }
}
