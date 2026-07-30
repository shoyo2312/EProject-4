package com.tiktok.searchservice.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductSearchResponse(
        Long id,
        Long sellerId,
        String name,
        String description,
        BigDecimal price,
        String category,
        String imageUrl,
        Instant createdAt
) {
}
