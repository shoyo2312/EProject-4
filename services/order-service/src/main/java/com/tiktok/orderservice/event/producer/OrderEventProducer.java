package com.tiktok.orderservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.DomainEvent;
import com.tiktok.orderservice.entity.OutboxEvent;
import com.tiktok.orderservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private static final String AGGREGATE_TYPE = "ORDER";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publish(Long orderId, DomainEvent event) {
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(String.valueOf(orderId))
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build();

        outboxEventRepository.save(outboxEvent);
    }
}
