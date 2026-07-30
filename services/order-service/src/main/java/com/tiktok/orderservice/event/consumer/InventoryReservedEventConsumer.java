package com.tiktok.orderservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.inventory.InventoryReservedEvent;
import com.tiktok.orderservice.entity.InboxEvent;
import com.tiktok.orderservice.repository.InboxEventRepository;
import com.tiktok.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class InventoryReservedEventConsumer {

    private final OrderService orderService;
    private final InboxEventRepository inboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "inventory.reserved-events", groupId = "order-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        InventoryReservedEvent event = objectMapper.readValue(payload, InventoryReservedEvent.class);

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        orderService.markAwaitingPayment(event.orderId());

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
