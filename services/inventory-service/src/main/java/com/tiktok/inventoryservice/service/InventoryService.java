package com.tiktok.inventoryservice.service;

import com.tiktok.event.order.OrderItem;
import com.tiktok.inventoryservice.dto.response.InventoryResponse;

import java.util.List;

public interface InventoryService {

    /**
     * Reserves stock for every item of an order in one transaction: if any product is
     * missing or under-stocked, the whole reservation is rolled back so a partially
     * fulfilled order never happens.
     */
    void reserveStock(Long orderId, List<OrderItem> items);

    /**
     * Compensating action: returns every RESERVED quantity recorded for this order back
     * to available stock (order cancelled or payment failed).
     */
    void releaseStock(Long orderId);

    /**
     * Finalizes every RESERVED quantity recorded for this order (payment succeeded) —
     * the stock leaves inventory for good.
     */
    void confirmStock(Long orderId);

    /**
     * Creates a zero-stock inventory row for a newly published product, if one doesn't
     * already exist. Idempotent so it can be called from a Kafka consumer.
     */
    void provisionForProduct(Long productId, Long sellerId);

    InventoryResponse restock(Long sellerId, Long productId, int quantity);

    InventoryResponse getStock(Long productId);
}
