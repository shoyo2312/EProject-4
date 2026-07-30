package com.tiktok.cartservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class CartItemNotFoundException extends ResourceNotFoundException {

    public CartItemNotFoundException(Long productId) {
        super("CART_ITEM_NOT_FOUND", "Product not in cart: " + productId);
    }
}
