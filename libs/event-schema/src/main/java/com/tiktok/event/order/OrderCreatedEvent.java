package com.tiktok.event.order;

import com.tiktok.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderCreatedEvent(
        String eventId,
        Instant occurredAt,
        Long orderId,
        Long userId,
        BigDecimal totalAmount,
        List<OrderItem> items
) implements DomainEvent {

    public static OrderCreatedEvent of(Long orderId, Long userId, BigDecimal totalAmount, List<OrderItem> items) {
        return new OrderCreatedEvent(UUID.randomUUID().toString(), Instant.now(), orderId, userId, totalAmount, items);
    }
}
