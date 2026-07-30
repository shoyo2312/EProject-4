package com.tiktok.paymentservice.service;

import com.tiktok.event.order.OrderItem;
import com.tiktok.paymentservice.dto.response.PaymentResponse;

import java.util.List;

public interface PaymentService {

    /**
     * Records which user an order belongs to, from OrderCreatedEvent -- needed later
     * because InventoryReservedEvent (the actual charge trigger) carries no userId.
     */
    void recordOrderReference(Long orderId, Long userId);

    /**
     * Charges the order once its stock has been reserved, and publishes
     * PaymentCompletedEvent or PaymentFailedEvent depending on the outcome.
     */
    void processPayment(Long orderId, List<OrderItem> items);

    PaymentResponse getByOrderId(Long userId, Long orderId);

    List<PaymentResponse> listByUser(Long userId);
}
