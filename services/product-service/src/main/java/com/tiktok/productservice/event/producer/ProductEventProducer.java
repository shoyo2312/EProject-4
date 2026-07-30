package com.tiktok.productservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.product.ProductCreatedEvent;
import com.tiktok.productservice.entity.OutboxEvent;
import com.tiktok.productservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductEventProducer {

    private static final String AGGREGATE_TYPE = "PRODUCT";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publishProductCreated(ProductCreatedEvent event) {
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(String.valueOf(event.productId()))
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build();

        outboxEventRepository.save(outboxEvent);
    }
}
