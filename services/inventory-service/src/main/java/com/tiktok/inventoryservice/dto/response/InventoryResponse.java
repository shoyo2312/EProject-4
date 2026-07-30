package com.tiktok.inventoryservice.dto.response;

public record InventoryResponse(
        Long productId,
        int availableQuantity,
        int reservedQuantity
) {
}
