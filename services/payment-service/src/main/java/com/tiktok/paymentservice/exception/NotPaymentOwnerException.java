package com.tiktok.paymentservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotPaymentOwnerException extends ForbiddenException {

    public NotPaymentOwnerException(Long orderId) {
        super("NOT_PAYMENT_OWNER", "You do not own the payment for order: " + orderId);
    }
}
