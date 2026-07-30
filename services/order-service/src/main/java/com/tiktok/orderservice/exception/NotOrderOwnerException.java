package com.tiktok.orderservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotOrderOwnerException extends ForbiddenException {

    public NotOrderOwnerException(Long orderId) {
        super("NOT_ORDER_OWNER", "You do not own order: " + orderId);
    }
}
