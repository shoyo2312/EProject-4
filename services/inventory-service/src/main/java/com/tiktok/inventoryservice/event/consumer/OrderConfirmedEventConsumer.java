package com.tiktok.inventoryservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.order.OrderConfirmedEvent;
import com.tiktok.inventoryservice.entity.InboxEvent;
import com.tiktok.inventoryservice.repository.InboxEventRepository;
import com.tiktok.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderConfirmedEventConsumer {

    private final InventoryService inventoryService;
    private final InboxEventRepository inboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.confirmed-events", groupId = "inventory-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        OrderConfirmedEvent event = objectMapper.readValue(payload, OrderConfirmedEvent.class);

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        inventoryService.confirmStock(event.orderId());

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
