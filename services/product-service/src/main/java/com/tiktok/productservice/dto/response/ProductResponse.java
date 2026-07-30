package com.tiktok.productservice.dto.response;

import com.tiktok.productservice.entity.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        Long sellerId,
        String name,
        String description,
        BigDecimal price,
        String category,
        String imageUrl,
        ProductStatus status,
        Instant createdAt
) {
}
