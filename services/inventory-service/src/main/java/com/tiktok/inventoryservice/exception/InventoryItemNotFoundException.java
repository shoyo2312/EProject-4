package com.tiktok.inventoryservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class InventoryItemNotFoundException extends ResourceNotFoundException {

    public InventoryItemNotFoundException(Long productId) {
        super("INVENTORY_ITEM_NOT_FOUND", "No inventory record for product: " + productId);
    }
}
