package com.tiktok.event.payment;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record PaymentFailedEvent(
        String eventId,
        Instant occurredAt,
        Long paymentId,
        Long orderId,
        String reason
) implements DomainEvent {

    public static PaymentFailedEvent of(Long paymentId, Long orderId, String reason) {
        return new PaymentFailedEvent(UUID.randomUUID().toString(), Instant.now(), paymentId, orderId, reason);
    }
}
