package com.tiktok.orderservice.service;

import com.tiktok.event.order.OrderCancelledEvent;
import com.tiktok.event.order.OrderConfirmedEvent;
import com.tiktok.event.order.OrderCreatedEvent;
import com.tiktok.event.order.OrderItem;
import com.tiktok.orderservice.client.CartClient;
import com.tiktok.orderservice.client.CartSummary;
import com.tiktok.orderservice.dto.response.OrderItemResponse;
import com.tiktok.orderservice.dto.response.OrderResponse;
import com.tiktok.orderservice.entity.Order;
import com.tiktok.orderservice.entity.OrderLineItem;
import com.tiktok.orderservice.entity.OrderStatus;
import com.tiktok.orderservice.event.producer.OrderEventProducer;
import com.tiktok.orderservice.exception.EmptyCartException;
import com.tiktok.orderservice.exception.NotOrderOwnerException;
import com.tiktok.orderservice.exception.OrderNotFoundException;
import com.tiktok.orderservice.mapper.OrderItemMapper;
import com.tiktok.orderservice.repository.OrderLineItemRepository;
import com.tiktok.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderLineItemRepository orderLineItemRepository;
    private final CartClient cartClient;
    private final OrderEventProducer orderEventProducer;
    private final OrderItemMapper orderItemMapper;

    @Override
    @Transactional
    public OrderResponse checkout(Long userId, String bearerToken) {
        CartSummary cart = cartClient.getCart(bearerToken);
        if (cart == null || cart.items() == null || cart.items().isEmpty()) {
            throw new EmptyCartException();
        }

        Order order = orderRepository.save(Order.builder()
                .userId(userId)
                .totalAmount(cart.totalAmount())
                .status(OrderStatus.PENDING)
                .build());

        List<OrderLineItem> lineItems = cart.items().stream()
                .map(cartItem -> orderLineItemRepository.save(OrderLineItem.builder()
                        .orderId(order.getId())
                        .productId(cartItem.productId())
                        .productName(cartItem.productName())
                        .quantity(cartItem.quantity())
                        .price(cartItem.price())
                        .build()))
                .toList();

        List<OrderItem> eventItems = cart.items().stream()
                .map(cartItem -> new OrderItem(cartItem.productId(), cartItem.quantity(), cartItem.price()))
                .toList();
        orderEventProducer.publish(order.getId(), OrderCreatedEvent.of(order.getId(), userId, order.getTotalAmount(), eventItems));

        cartClient.clearCart(bearerToken);

        return toResponse(order, lineItems);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getById(Long userId, Long orderId) {
        Order order = findVisible(orderId);

        if (!userId.equals(order.getUserId())) {
            throw new NotOrderOwnerException(orderId);
        }

        return toResponse(order, orderLineItemRepository.findByOrderId(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> listByUser(Long userId) {
        return orderRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(order -> toResponse(order, orderLineItemRepository.findByOrderId(order.getId())))
                .toList();
    }

    @Override
    @Transactional
    public OrderResponse cancel(Long userId, Long orderId, String reason) {
        Order order = findVisible(orderId);

        if (!userId.equals(order.getUserId())) {
            throw new NotOrderOwnerException(orderId);
        }

        order.cancelByCustomer(reason);
        orderEventProducer.publish(orderId, OrderCancelledEvent.of(orderId, userId, reason));

        return toResponse(order, orderLineItemRepository.findByOrderId(orderId));
    }

    @Override
    @Transactional
    public void markAwaitingPayment(Long orderId) {
        findVisible(orderId).markAwaitingPayment();
    }

    @Override
    @Transactional
    public void cancelFromSaga(Long orderId, String reason) {
        Order order = findVisible(orderId);
        order.markCancelled(reason);
        orderEventProducer.publish(orderId, OrderCancelledEvent.of(orderId, order.getUserId(), reason));
    }

    @Override
    @Transactional
    public void confirm(Long orderId) {
        Order order = findVisible(orderId);
        order.markConfirmed();
        orderEventProducer.publish(orderId, OrderConfirmedEvent.of(orderId, order.getUserId()));
    }

    private Order findVisible(Long orderId) {
        return orderRepository.findByIdAndDeletedAtIsNull(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private OrderResponse toResponse(Order order, List<OrderLineItem> items) {
        List<OrderItemResponse> itemResponses = items.stream().map(orderItemMapper::toResponse).toList();
        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(), order.getTotalAmount(),
                order.getCancelReason(), itemResponses, order.getCreatedAt());
    }
}
