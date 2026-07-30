package com.tiktok.orderservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.payment.PaymentCompletedEvent;
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
public class PaymentCompletedEventConsumer {

    private final OrderService orderService;
    private final InboxEventRepository inboxEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment.completed-events", groupId = "order-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);

        if (inboxEventRepository.existsByEventId(event.eventId())) {
            return;
        }

        orderService.confirm(event.orderId());

        inboxEventRepository.save(InboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .build());
    }
}
