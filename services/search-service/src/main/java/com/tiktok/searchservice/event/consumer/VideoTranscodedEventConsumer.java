package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
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
public class VideoTranscodedEventConsumer {

    private final VideoDocumentRepository videoDocumentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-transcoded-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoTranscodedEvent event = objectMapper.readValue(payload, VideoTranscodedEvent.class);

        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        videoDocumentRepository.findById(event.videoId()).ifPresent(document -> {
            if (event.success()) {
                document.markPublished(event.thumbnailUrl(), event.durationSeconds());
            } else {
                document.markFailed();
            }
            videoDocumentRepository.save(document);
        });

        processedEventRepository.save(ProcessedEventDocument.builder()
                .id(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .processedAt(Instant.now())
                .build());
    }
}
