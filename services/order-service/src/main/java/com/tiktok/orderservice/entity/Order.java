package com.tiktok.orderservice.entity;

import com.tiktok.common.entity.BaseEntity;
import com.tiktok.orderservice.exception.InvalidOrderStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "cancel_reason")
    private String cancelReason;

    public void markAwaitingPayment() {
        this.status = OrderStatus.AWAITING_PAYMENT;
    }

    public void markConfirmed() {
        this.status = OrderStatus.CONFIRMED;
    }

    public void markCancelled(String reason) {
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
    }

    /**
     * User-initiated cancellation is only allowed before the saga has reached a terminal
     * state — once CONFIRMED or CANCELLED, the customer can't undo it through this path.
     */
    public void cancelByCustomer(String reason) {
        if (status != OrderStatus.PENDING && status != OrderStatus.AWAITING_PAYMENT) {
            throw new InvalidOrderStateException(getId(), status.name());
        }
        markCancelled(reason);
    }
}
