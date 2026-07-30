package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.searchservice.document.ProcessedEventDocument;
import com.tiktok.searchservice.repository.ProcessedEventRepository;
import com.tiktok.searchservice.repository.VideoDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class VideoSharedEventConsumer {

    private final VideoDocumentRepository videoDocumentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.share-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoSharedEvent event = objectMapper.readValue(payload, VideoSharedEvent.class);

        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        videoDocumentRepository.findById(String.valueOf(event.videoId())).ifPresent(document -> {
            document.incrementShareCount();
            videoDocumentRepository.save(document);
        });

        processedEventRepository.save(ProcessedEventDocument.builder()
                .id(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .processedAt(Instant.now())
                .build());
    }
}
