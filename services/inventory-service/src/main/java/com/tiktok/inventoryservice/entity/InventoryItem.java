package com.tiktok.inventoryservice.entity;

import com.tiktok.common.entity.BaseEntity;
import com.tiktok.inventoryservice.exception.InsufficientStockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "inventory_items")
public class InventoryItem extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /**
     * Moves stock from available into reserved. The {@code @Version} on {@link BaseEntity}
     * guards against a lost update if two orders race for the same product concurrently —
     * the loser's transaction fails with an optimistic lock exception and the caller (a
     * Kafka consumer) simply fails the message, relying on redelivery to retry.
     */
    public void reserve(int quantity) {
        if (this.availableQuantity < quantity) {
            throw new InsufficientStockException(this.productId, quantity, this.availableQuantity);
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
    }

    /**
     * Compensating action for a cancelled order / failed payment: gives the reserved
     * quantity back to available stock.
     */
    public void release(int quantity) {
        this.availableQuantity += quantity;
        this.reservedQuantity -= quantity;
    }

    /**
     * Finalizes a sale: the reserved quantity leaves inventory for good (it was already
     * subtracted from available at reservation time, so only the reserved bucket shrinks).
     */
    public void confirm(int quantity) {
        this.reservedQuantity -= quantity;
    }

    public void restock(int quantity) {
        this.availableQuantity += quantity;
    }
}
