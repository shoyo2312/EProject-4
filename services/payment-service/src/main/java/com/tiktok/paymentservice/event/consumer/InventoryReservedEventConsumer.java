package com.tiktok.paymentservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.inventory.InventoryReservedEvent;
import com.tiktok.paymentservice.entity.InboxEvent;
import com.tiktok.paymentservice.repository.InboxEventRepository;
import com.tiktok.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock being reserved is what triggers a charge attempt here -- there is no synchronous
 * "please charge this order" call from order-service; the saga is choreographed purely
 * through these events.
 */
@Component
@RequiredArgsConstructor
public class InventoryReservedEventConsumer {

    private final PaymentService paymentService;
    private final InboxEventRepository inboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "inventory.reserved-events", groupId = "payment-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        InventoryReservedEvent event = objectMapper.readValue(payload, InventoryReservedEvent.class);

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        paymentService.processPayment(event.orderId(), event.items());

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
