package com.tiktok.paymentservice.gateway;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Stand-in for a real payment processor (Stripe, VNPay, ...): no such integration exists
 * in this project, and nothing upstream collects a card/wallet to charge against, so this
 * deterministically succeeds for any positive amount. Swap this out once a real gateway
 * and payment-method capture exist.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    @Override
    public PaymentGatewayResult charge(Long orderId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return PaymentGatewayResult.failed("Invalid charge amount: " + amount);
        }
        return PaymentGatewayResult.succeeded();
    }
}
