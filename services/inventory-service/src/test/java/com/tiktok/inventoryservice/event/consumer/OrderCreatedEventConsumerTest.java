package com.tiktok.inventoryservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.DomainEvent;
import com.tiktok.event.inventory.InventoryReservationFailedEvent;
import com.tiktok.event.inventory.InventoryReservedEvent;
import com.tiktok.event.order.OrderCreatedEvent;
import com.tiktok.event.order.OrderItem;
import com.tiktok.inventoryservice.event.producer.InventoryEventProducer;
import com.tiktok.inventoryservice.exception.InsufficientStockException;
import com.tiktok.inventoryservice.repository.InboxEventRepository;
import com.tiktok.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCreatedEventConsumerTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private InventoryEventProducer inventoryEventProducer;

    @Mock
    private InboxEventRepository inboxEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void onMessage_reservationSucceeds_publishesReservedEvent() throws Exception {
        OrderCreatedEventConsumer consumer = new OrderCreatedEventConsumer(inventoryService, inventoryEventProducer, inboxEventRepository, objectMapper);
        OrderCreatedEvent event = OrderCreatedEvent.of(900L, 1L, new BigDecimal("9.99"), List.of(new OrderItem(1L, 2, new BigDecimal("9.99"))));
        when(inboxEventRepository.existsByEventId(event.eventId())).thenReturn(false);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        verify(inventoryService).reserveStock(eq(900L), any());
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(inventoryEventProducer).publish(eq(900L), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(InventoryReservedEvent.class);
        verify(inboxEventRepository).save(any());
    }

    @Test
    void onMessage_reservationFails_publishesReservationFailedEvent() throws Exception {
        OrderCreatedEventConsumer consumer = new OrderCreatedEventConsumer(inventoryService, inventoryEventProducer, inboxEventRepository, objectMapper);
        OrderCreatedEvent event = OrderCreatedEvent.of(900L, 1L, new BigDecimal("9.99"), List.of(new OrderItem(1L, 2, new BigDecimal("9.99"))));
        when(inboxEventRepository.existsByEventId(event.eventId())).thenReturn(false);
        doThrow(new InsufficientStockException(1L, 2, 0)).when(inventoryService).reserveStock(eq(900L), any());

        consumer.onMessage(objectMapper.writeValueAsString(event));

        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(inventoryEventProducer).publish(eq(900L), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(InventoryReservationFailedEvent.class);
        verify(inboxEventRepository).save(any());
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        OrderCreatedEventConsumer consumer = new OrderCreatedEventConsumer(inventoryService, inventoryEventProducer, inboxEventRepository, objectMapper);
        OrderCreatedEvent event = OrderCreatedEvent.of(900L, 1L, new BigDecimal("9.99"), List.of(new OrderItem(1L, 2, new BigDecimal("9.99"))));
        when(inboxEventRepository.existsByEventId(event.eventId())).thenReturn(true);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        verifyNoInteractions(inventoryService, inventoryEventProducer);
        verify(inboxEventRepository, never()).save(any());
    }
}
