package com.tiktok.paymentservice.gateway;

public record PaymentGatewayResult(
        boolean success,
        String failureReason
) {

    public static PaymentGatewayResult succeeded() {
        return new PaymentGatewayResult(true, null);
    }

    public static PaymentGatewayResult failed(String reason) {
        return new PaymentGatewayResult(false, reason);
    }
}
