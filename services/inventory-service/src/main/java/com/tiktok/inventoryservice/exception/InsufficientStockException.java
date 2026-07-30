package com.tiktok.inventoryservice.exception;

import com.tiktok.common.exception.ConflictException;

public class InsufficientStockException extends ConflictException {

    public InsufficientStockException(Long productId, int requested, int available) {
        super("INSUFFICIENT_STOCK", "Insufficient stock for product " + productId
                + ": requested " + requested + ", available " + available);
    }
}
