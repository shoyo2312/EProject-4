package com.tiktok.paymentservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Plain insert-only lookup, not a {@code BaseEntity} (no soft-delete/version/updatedAt) --
 * it's a fact recorded once from OrderCreatedEvent, read back when InventoryReservedEvent
 * arrives for the same order so payment can be charged to the right user.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "order_references")
public class OrderReference {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = SnowflakeIdGenerator.nextId();
        }
        createdAt = Instant.now();
    }
}
