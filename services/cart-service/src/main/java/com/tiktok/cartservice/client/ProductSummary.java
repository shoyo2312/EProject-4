package com.tiktok.cartservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Subset of product-service's ProductResponse this client actually needs — extra fields
 * (description, category, sellerId, ...) are ignored on deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSummary(
        Long id,
        String name,
        BigDecimal price
) {
}
