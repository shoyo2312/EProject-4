package com.tiktok.orderservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartItemSummary(
        Long productId,
        String productName,
        BigDecimal price,
        int quantity
) {
}
