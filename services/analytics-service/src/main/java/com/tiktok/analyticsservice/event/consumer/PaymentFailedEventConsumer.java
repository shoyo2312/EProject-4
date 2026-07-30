package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.RevenueEventRepository;
import com.tiktok.event.payment.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentFailedEventConsumer {

    private final RevenueEventRepository revenueEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "payment.failed-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        PaymentFailedEvent event = objectMapper.readValue(payload, PaymentFailedEvent.class);
        revenueEventRepository.insert(event.eventId(), "PAYMENT_FAILED", event.orderId(), null, null, event.occurredAt());
    }
}
