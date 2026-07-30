package com.tiktok.cartservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class ProductNotFoundException extends ResourceNotFoundException {

    public ProductNotFoundException(Long productId) {
        super("PRODUCT_NOT_FOUND", "Product not found: " + productId);
    }
}
