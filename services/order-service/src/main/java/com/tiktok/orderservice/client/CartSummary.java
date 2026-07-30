package com.tiktok.orderservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CartSummary(
        List<CartItemSummary> items,
        BigDecimal totalAmount
) {
}
