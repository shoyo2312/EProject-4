package com.tiktok.event.payment;

import com.tiktok.event.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCompletedEvent(
        String eventId,
        Instant occurredAt,
        Long paymentId,
        Long orderId,
        BigDecimal amount
) implements DomainEvent {

    public static PaymentCompletedEvent of(Long paymentId, Long orderId, BigDecimal amount) {
        return new PaymentCompletedEvent(UUID.randomUUID().toString(), Instant.now(), paymentId, orderId, amount);
    }
}
