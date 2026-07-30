package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoLikeEvent;
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
public class VideoLikeEventConsumer {

    private final VideoDocumentRepository videoDocumentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.like-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoLikeEvent event = objectMapper.readValue(payload, VideoLikeEvent.class);

        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        videoDocumentRepository.findById(String.valueOf(event.videoId())).ifPresent(document -> {
            document.applyLikeDelta(event.liked() ? 1 : -1);
            videoDocumentRepository.save(document);
        });

        processedEventRepository.save(ProcessedEventDocument.builder()
                .id(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .processedAt(Instant.now())
                .build());
    }
}
