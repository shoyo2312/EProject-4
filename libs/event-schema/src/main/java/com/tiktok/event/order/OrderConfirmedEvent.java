package com.tiktok.event.order;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record OrderConfirmedEvent(
        String eventId,
        Instant occurredAt,
        Long orderId,
        Long userId
) implements DomainEvent {

    public static OrderConfirmedEvent of(Long orderId, Long userId) {
        return new OrderConfirmedEvent(UUID.randomUUID().toString(), Instant.now(), orderId, userId);
    }
}
