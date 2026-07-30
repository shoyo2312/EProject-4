package com.tiktok.inventoryservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Per-(order, product) ledger row recorded at reservation time. Needed because compensating
 * events (OrderCancelledEvent, PaymentFailedEvent) carry only an orderId, not the item list —
 * this table is what lets releaseStock/confirmStock know which products and quantities to
 * roll back or finalize for that order.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "stock_reservations")
public class StockReservation extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    public void markReleased() {
        this.status = ReservationStatus.RELEASED;
    }

    public void markConfirmed() {
        this.status = ReservationStatus.CONFIRMED;
    }
}
