package com.tiktok.paymentservice.dto.response;

import com.tiktok.paymentservice.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        Long orderId,
        Long userId,
        BigDecimal amount,
        PaymentStatus status,
        String failureReason,
        Instant createdAt
) {
}
