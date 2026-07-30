package com.tiktok.paymentservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class OrderReferenceNotFoundException extends ResourceNotFoundException {

    public OrderReferenceNotFoundException(Long orderId) {
        super("ORDER_REFERENCE_NOT_FOUND", "No order reference recorded for order: " + orderId);
    }
}
