package com.tiktok.orderservice.service;

import com.tiktok.orderservice.dto.response.OrderResponse;

import java.util.List;

public interface OrderService {

    OrderResponse checkout(Long userId, String bearerToken);

    OrderResponse getById(Long userId, Long orderId);

    List<OrderResponse> listByUser(Long userId);

    OrderResponse cancel(Long userId, Long orderId, String reason);

    void markAwaitingPayment(Long orderId);

    void cancelFromSaga(Long orderId, String reason);

    void confirm(Long orderId);
}
