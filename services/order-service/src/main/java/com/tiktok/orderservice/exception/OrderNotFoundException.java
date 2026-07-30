package com.tiktok.orderservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class OrderNotFoundException extends ResourceNotFoundException {

    public OrderNotFoundException(Long orderId) {
        super("ORDER_NOT_FOUND", "Order not found: " + orderId);
    }
}
