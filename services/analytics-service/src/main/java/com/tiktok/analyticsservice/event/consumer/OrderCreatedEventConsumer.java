package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.RevenueEventRepository;
import com.tiktok.event.order.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderCreatedEventConsumer {

    private final RevenueEventRepository revenueEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order.created-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
        revenueEventRepository.insert(event.eventId(), "ORDER_CREATED", event.orderId(), event.userId(), event.totalAmount(), event.occurredAt());
    }
}
