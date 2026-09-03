package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.product.ProductCreatedEvent;
import com.tiktok.searchservice.document.ProductDocument;
import com.tiktok.searchservice.repository.ProductDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductCreatedEventConsumer {

    private final ProductDocumentRepository productDocumentRepository;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "product.product-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        ProductCreatedEvent event = objectMapper.readValue(payload, ProductCreatedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                productDocumentRepository.save(ProductDocument.builder()
                        .id(event.productId())
                        .sellerId(event.sellerId())
                        .name(event.name())
                        .description(event.description())
                        .price(event.price())
                        .category(event.category())
                        .imageUrl(event.imageUrl())
                        .status("ACTIVE")
                        .createdAt(event.occurredAt())
                        .build()));
    }
}
