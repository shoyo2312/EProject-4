package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.RevenueEventRepository;
import com.tiktok.event.order.OrderCancelledEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderCancelledEventConsumer {

    private final RevenueEventRepository revenueEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.cancelled-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        OrderCancelledEvent event = objectMapper.readValue(payload, OrderCancelledEvent.class);
        revenueEventRepository.insert(event.eventId(), "ORDER_CANCELLED", event.orderId(), event.userId(), null, event.occurredAt());
    }
}
