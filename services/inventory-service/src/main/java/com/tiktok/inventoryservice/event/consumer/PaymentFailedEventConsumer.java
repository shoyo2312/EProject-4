package com.tiktok.inventoryservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.inventory.InventoryReleasedEvent;
import com.tiktok.event.payment.PaymentFailedEvent;
import com.tiktok.inventoryservice.entity.InboxEvent;
import com.tiktok.inventoryservice.event.producer.InventoryEventProducer;
import com.tiktok.inventoryservice.repository.InboxEventRepository;
import com.tiktok.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFailedEventConsumer {

    private final InventoryService inventoryService;
    private final InventoryEventProducer inventoryEventProducer;
    private final InboxEventRepository inboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment.failed-events", groupId = "inventory-service")
    @SneakyThrows
    public void onMessage(String payload) {
        PaymentFailedEvent event = objectMapper.readValue(payload, PaymentFailedEvent.class);

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        inventoryService.releaseStock(event.orderId());
        inventoryEventProducer.publish(event.orderId(), InventoryReleasedEvent.of(event.orderId(), "Payment failed: " + event.reason()));

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
