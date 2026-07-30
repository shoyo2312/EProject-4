package com.tiktok.inventoryservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.common.exception.DomainException;
import com.tiktok.event.inventory.InventoryReservationFailedEvent;
import com.tiktok.event.inventory.InventoryReservedEvent;
import com.tiktok.event.order.OrderCreatedEvent;
import com.tiktok.inventoryservice.entity.InboxEvent;
import com.tiktok.inventoryservice.event.producer.InventoryEventProducer;
import com.tiktok.inventoryservice.repository.InboxEventRepository;
import com.tiktok.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * No blanket @Transactional here: {@link InventoryService#reserveStock} owns its own
 * transaction so a mid-order failure rolls back only the reservation attempt, while the
 * inbox row and outcome event below are written in their own separate transactions after
 * the catch — keeping them out of a transaction Spring may have already marked
 * rollback-only.
 */
@Component
@RequiredArgsConstructor
public class OrderCreatedEventConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventProducer inventoryEventProducer;
    private final InboxEventRepository inboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.created-events", groupId = "inventory-service")
    @SneakyThrows
    public void onMessage(String payload) {
        OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        try {
            inventoryService.reserveStock(event.orderId(), event.items());
            inventoryEventProducer.publish(event.orderId(), InventoryReservedEvent.of(event.orderId(), event.items()));
        } catch (DomainException e) {
            inventoryEventProducer.publish(event.orderId(), InventoryReservationFailedEvent.of(event.orderId(), e.getMessage()));
        }

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
