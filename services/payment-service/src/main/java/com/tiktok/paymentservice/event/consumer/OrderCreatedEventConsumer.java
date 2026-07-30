package com.tiktok.paymentservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.order.OrderCreatedEvent;
import com.tiktok.paymentservice.entity.InboxEvent;
import com.tiktok.paymentservice.repository.InboxEventRepository;
import com.tiktok.paymentservice.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderCreatedEventConsumer {

    private final PaymentService paymentService;
    private final InboxEventRepository inboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.created-events", groupId = "payment-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        paymentService.recordOrderReference(event.orderId(), event.userId());

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
