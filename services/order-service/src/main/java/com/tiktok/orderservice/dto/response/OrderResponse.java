package com.tiktok.orderservice.dto.response;

import com.tiktok.orderservice.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        BigDecimal totalAmount,
        String cancelReason,
        List<OrderItemResponse> items,
        Instant createdAt
) {
}
