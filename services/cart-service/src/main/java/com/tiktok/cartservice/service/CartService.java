package com.tiktok.cartservice.service;

import com.tiktok.cartservice.dto.response.CartResponse;

public interface CartService {

    CartResponse getCart(Long userId);

    CartResponse addItem(Long userId, Long productId, int quantity);

    CartResponse updateItemQuantity(Long userId, Long productId, int quantity);

    CartResponse removeItem(Long userId, Long productId);

    void clearCart(Long userId);
}
