package com.tiktok.orderservice.service;

import com.tiktok.event.DomainEvent;
import com.tiktok.event.order.OrderCancelledEvent;
import com.tiktok.event.order.OrderConfirmedEvent;
import com.tiktok.event.order.OrderCreatedEvent;
import com.tiktok.orderservice.client.CartClient;
import com.tiktok.orderservice.client.CartItemSummary;
import com.tiktok.orderservice.client.CartSummary;
import com.tiktok.orderservice.dto.response.OrderItemResponse;
import com.tiktok.orderservice.dto.response.OrderResponse;
import com.tiktok.orderservice.entity.Order;
import com.tiktok.orderservice.entity.OrderLineItem;
import com.tiktok.orderservice.entity.OrderStatus;
import com.tiktok.orderservice.event.producer.OrderEventProducer;
import com.tiktok.orderservice.exception.EmptyCartException;
import com.tiktok.orderservice.exception.InvalidOrderStateException;
import com.tiktok.orderservice.exception.NotOrderOwnerException;
import com.tiktok.orderservice.exception.OrderNotFoundException;
import com.tiktok.orderservice.mapper.OrderItemMapper;
import com.tiktok.orderservice.repository.OrderLineItemRepository;
import com.tiktok.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderLineItemRepository orderLineItemRepository;

    @Mock
    private CartClient cartClient;

    @Mock
    private OrderEventProducer orderEventProducer;

    @Mock
    private OrderItemMapper orderItemMapper;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderRepository, orderLineItemRepository, cartClient, orderEventProducer, orderItemMapper);
        lenientStubMapper();
    }

    private void lenientStubMapper() {
        lenient().when(orderItemMapper.toResponse(any(OrderLineItem.class))).thenAnswer(invocation -> {
            OrderLineItem item = invocation.getArgument(0);
            return new OrderItemResponse(item.getProductId(), item.getProductName(), item.getQuantity(), item.getPrice(),
                    item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        });
    }

    private Order order(Long id, Long userId, OrderStatus status) {
        return Order.builder().id(id).userId(userId).totalAmount(new BigDecimal("19.98")).status(status).createdAt(Instant.now()).build();
    }

    @Test
    void checkout_withItemsInCart_createsOrderPublishesEventAndClearsCart() {
        CartSummary cart = new CartSummary(List.of(new CartItemSummary(1L, "Phone case", new BigDecimal("9.99"), 2)), new BigDecimal("19.98"));
        when(cartClient.getCart("Bearer token")).thenReturn(cart);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            return Order.builder().id(900L).userId(o.getUserId()).totalAmount(o.getTotalAmount()).status(o.getStatus()).createdAt(Instant.now()).build();
        });
        when(orderLineItemRepository.save(any(OrderLineItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.checkout(100L, "Bearer token");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getUserId()).isEqualTo(100L);
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(orderCaptor.getValue().getTotalAmount()).isEqualByComparingTo("19.98");

        ArgumentCaptor<OrderLineItem> lineItemCaptor = ArgumentCaptor.forClass(OrderLineItem.class);
        verify(orderLineItemRepository).save(lineItemCaptor.capture());
        assertThat(lineItemCaptor.getValue().getProductId()).isEqualTo(1L);
        assertThat(lineItemCaptor.getValue().getQuantity()).isEqualTo(2);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(orderEventProducer).publish(eq(900L), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(OrderCreatedEvent.class);

        verify(cartClient).clearCart("Bearer token");
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void checkout_emptyCart_throwsEmptyCartExceptionAndDoesNotCreateOrder() {
        when(cartClient.getCart("Bearer token")).thenReturn(new CartSummary(List.of(), BigDecimal.ZERO));

        assertThatThrownBy(() -> orderService.checkout(100L, "Bearer token"))
                .isInstanceOf(EmptyCartException.class);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void getById_calledByOwner_returnsMappedResponse() {
        Order order = order(900L, 100L, OrderStatus.PENDING);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));
        when(orderLineItemRepository.findByOrderId(900L)).thenReturn(List.of());

        OrderResponse response = orderService.getById(100L, 900L);

        assertThat(response.id()).isEqualTo(900L);
        assertThat(response.userId()).isEqualTo(100L);
    }

    @Test
    void getById_calledByNonOwner_throwsNotOrderOwnerException() {
        Order order = order(900L, 100L, OrderStatus.PENDING);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getById(200L, 900L))
                .isInstanceOf(NotOrderOwnerException.class);
    }

    @Test
    void getById_missingOrder_throwsOrderNotFoundException() {
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getById(100L, 900L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void listByUser_mapsAllOrdersForUser() {
        Order order1 = order(900L, 100L, OrderStatus.PENDING);
        Order order2 = order(901L, 100L, OrderStatus.CONFIRMED);
        when(orderRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(100L)).thenReturn(List.of(order1, order2));
        when(orderLineItemRepository.findByOrderId(900L)).thenReturn(List.of());
        when(orderLineItemRepository.findByOrderId(901L)).thenReturn(List.of());

        List<OrderResponse> responses = orderService.listByUser(100L);

        assertThat(responses).hasSize(2);
    }

    @Test
    void cancel_ownerCancelsPendingOrder_marksCancelledAndPublishesEvent() {
        Order order = order(900L, 100L, OrderStatus.PENDING);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));
        when(orderLineItemRepository.findByOrderId(900L)).thenReturn(List.of());

        OrderResponse response = orderService.cancel(100L, 900L, "Changed my mind");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("Changed my mind");
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(orderEventProducer).publish(eq(900L), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(OrderCancelledEvent.class);
    }

    @Test
    void cancel_calledByNonOwner_throwsNotOrderOwnerException() {
        Order order = order(900L, 100L, OrderStatus.PENDING);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(200L, 900L, "Not mine"))
                .isInstanceOf(NotOrderOwnerException.class);
        verifyNoInteractions(orderEventProducer);
    }

    @Test
    void cancel_alreadyConfirmedOrder_throwsInvalidOrderStateException() {
        Order order = order(900L, 100L, OrderStatus.CONFIRMED);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancel(100L, 900L, "Too late"))
                .isInstanceOf(InvalidOrderStateException.class);
        verifyNoInteractions(orderEventProducer);
    }

    @Test
    void markAwaitingPayment_updatesOrderStatus() {
        Order order = order(900L, 100L, OrderStatus.PENDING);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));

        orderService.markAwaitingPayment(900L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @Test
    void cancelFromSaga_marksCancelledAndPublishesEventUsingOrderOwnerUserId() {
        Order order = order(900L, 100L, OrderStatus.AWAITING_PAYMENT);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));

        orderService.cancelFromSaga(900L, "Insufficient stock");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("Insufficient stock");
        verify(orderEventProducer).publish(eq(900L), any(OrderCancelledEvent.class));
    }

    @Test
    void confirm_marksConfirmedAndPublishesEvent() {
        Order order = order(900L, 100L, OrderStatus.AWAITING_PAYMENT);
        when(orderRepository.findByIdAndDeletedAtIsNull(900L)).thenReturn(Optional.of(order));

        orderService.confirm(900L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderEventProducer).publish(eq(900L), any(OrderConfirmedEvent.class));
    }
}
