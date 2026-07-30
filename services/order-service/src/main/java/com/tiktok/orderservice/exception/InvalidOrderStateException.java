package com.tiktok.orderservice.exception;

import com.tiktok.common.exception.ConflictException;

public class InvalidOrderStateException extends ConflictException {

    public InvalidOrderStateException(Long orderId, String currentStatus) {
        super("INVALID_ORDER_STATE", "Order " + orderId + " cannot be cancelled from state " + currentStatus);
    }
}
