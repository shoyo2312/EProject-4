package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.searchservice.document.ProcessedEventDocument;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.repository.ProcessedEventRepository;
import com.tiktok.searchservice.repository.VideoDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class VideoPublishedEventConsumer {

    private final VideoDocumentRepository videoDocumentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoPublishedEvent event = objectMapper.readValue(payload, VideoPublishedEvent.class);

        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        VideoDocument document = VideoDocument.builder()
                .id(event.videoId())
                .userId(event.userId())
                .title(event.title())
                .status("PROCESSING")
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .shareCount(0)
                .createdAt(event.occurredAt())
                .build();
        videoDocumentRepository.save(document);

        processedEventRepository.save(ProcessedEventDocument.builder()
                .id(event.eventId())
                .eventType(event.getClass().getSimpleName())
                .processedAt(Instant.now())
                .build());
    }
}
