package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.RevenueEventRepository;
import com.tiktok.event.payment.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentCompletedEventConsumer {

    private final RevenueEventRepository revenueEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment.completed-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        PaymentCompletedEvent event = objectMapper.readValue(payload, PaymentCompletedEvent.class);
        revenueEventRepository.insert(event.eventId(), "PAYMENT_COMPLETED", event.orderId(), null, event.amount(), event.occurredAt());
    }
}
