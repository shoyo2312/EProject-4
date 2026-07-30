package com.tiktok.paymentservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class PaymentNotFoundException extends ResourceNotFoundException {

    public PaymentNotFoundException(Long orderId) {
        super("PAYMENT_NOT_FOUND", "No payment transaction recorded for order: " + orderId);
    }
}
