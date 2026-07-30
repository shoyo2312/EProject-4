package com.tiktok.orderservice.dto.response;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        String productName,
        int quantity,
        BigDecimal price,
        BigDecimal subtotal
) {
}
