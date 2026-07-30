package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.RevenueEventRepository;
import com.tiktok.event.order.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderConfirmedEventConsumer {

    private final RevenueEventRepository revenueEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.confirmed-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        OrderConfirmedEvent event = objectMapper.readValue(payload, OrderConfirmedEvent.class);
        revenueEventRepository.insert(event.eventId(), "ORDER_CONFIRMED", event.orderId(), event.userId(), null, event.occurredAt());
    }
}
