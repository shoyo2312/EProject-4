package com.tiktok.cartservice.cache;

import java.math.BigDecimal;

/**
 * JSON payload stored per product in the {@code cart:{userId}} Redis hash — the fast-path
 * read model. {@link com.tiktok.cartservice.entity.CartItem} in Postgres is the durable
 * backup this gets rehydrated from on a cache miss.
 */
public record CartItemData(
        Long productId,
        String productName,
        BigDecimal price,
        int quantity
) {
}
