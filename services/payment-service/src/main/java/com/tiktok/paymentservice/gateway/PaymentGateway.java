package com.tiktok.paymentservice.gateway;

import java.math.BigDecimal;

public interface PaymentGateway {

    PaymentGatewayResult charge(Long orderId, BigDecimal amount);
}
