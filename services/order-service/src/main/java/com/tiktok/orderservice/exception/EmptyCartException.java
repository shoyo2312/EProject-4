package com.tiktok.orderservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class EmptyCartException extends BadRequestException {

    public EmptyCartException() {
        super("EMPTY_CART", "Cannot check out an empty cart");
    }
}
