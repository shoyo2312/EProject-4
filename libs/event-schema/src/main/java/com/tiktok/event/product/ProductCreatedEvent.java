package com.tiktok.event.product;

import com.tiktok.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Carries the whole product, not just its price. search-service builds its product index from
 * this event and has no read path into product-service's database, so a field missing here is a
 * field no query can ever filter or match on — the category filter it exposes was matching
 * nothing at all while this event omitted the category.
 */
public record ProductCreatedEvent(
        String eventId,
        Instant occurredAt,
        Long productId,
        Long sellerId,
        String name,
        String description,
        BigDecimal price,
        String category,
        String imageUrl
) implements DomainEvent {

    public static ProductCreatedEvent of(Long productId, Long sellerId, String name, String description,
                                         BigDecimal price, String category, String imageUrl) {
        return new ProductCreatedEvent(UUID.randomUUID().toString(), Instant.now(), productId, sellerId,
                name, description, price, category, imageUrl);
    }
}
