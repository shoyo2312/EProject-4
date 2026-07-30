package com.tiktok.event.order;

import java.math.BigDecimal;

public record OrderItem(
        Long productId,
        int quantity,
        BigDecimal price
) {
}
