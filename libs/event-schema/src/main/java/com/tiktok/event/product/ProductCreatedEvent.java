package com.tiktok.event.product;

import com.tiktok.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductCreatedEvent(
        String eventId,
        Instant occurredAt,
        Long productId,
        Long sellerId,
        String name,
        BigDecimal price
) implements DomainEvent {

    public static ProductCreatedEvent of(Long productId, Long sellerId, String name, BigDecimal price) {
        return new ProductCreatedEvent(UUID.randomUUID().toString(), Instant.now(), productId, sellerId, name, price);
    }
}
