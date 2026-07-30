package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.product.ProductCreatedEvent;
import com.tiktok.searchservice.document.ProcessedEventDocument;
import com.tiktok.searchservice.document.ProductDocument;
import com.tiktok.searchservice.repository.ProcessedEventRepository;
import com.tiktok.searchservice.repository.ProductDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ProductCreatedEventConsumer {

    private final ProductDocumentRepository productDocumentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "product.product-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        ProductCreatedEvent event = objectMapper.readValue(payload, ProductCreatedEvent.class);

        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        ProductDocument document = ProductDocument.builder()
                .id(event.productId())
                .sellerId(event.sellerId())
                .name(event.name())
                .price(event.price())
                .status("ACTIVE")
                .createdAt(event.occurredAt())
                .build();
        productDocumentRepository.save(document);

        processedEventRepository.save(ProcessedEventDocument.builder()
                .id(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .processedAt(Instant.now())
                .build());
    }
}
