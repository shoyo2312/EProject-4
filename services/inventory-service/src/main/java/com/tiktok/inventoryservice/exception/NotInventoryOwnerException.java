package com.tiktok.inventoryservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotInventoryOwnerException extends ForbiddenException {

    public NotInventoryOwnerException(Long productId) {
        super("NOT_INVENTORY_OWNER", "You do not own the product for inventory item: " + productId);
    }
}
