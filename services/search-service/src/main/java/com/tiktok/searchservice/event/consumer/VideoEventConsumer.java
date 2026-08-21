package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.searchservice.document.ProcessedEventDocument;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.repository.ProcessedEventRepository;
import com.tiktok.searchservice.repository.VideoDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * video.video-events carries a publication and a deletion, both flat JSON objects with no type
 * field, so routing is on the eventType header video-service sets — see its VideoEventPublisher.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventConsumer {

    private static final String VIDEO_PUBLISHED = "VideoPublishedEvent";
    private static final String VIDEO_DELETED = "VideoDeletedEvent";

    private final VideoDocumentRepository videoDocumentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        // Absent header means a producer older than the deletion event, and everything that topic
        // carried then was a publication. Nothing else writes to it, so this is not a guess.
        String eventType = eventTypeHeader == null
                ? VIDEO_PUBLISHED
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (VIDEO_PUBLISHED.equals(eventType)) {
            handlePublished(objectMapper.readValue(payload, VideoPublishedEvent.class));
        } else if (VIDEO_DELETED.equals(eventType)) {
            handleDeleted(objectMapper.readValue(payload, VideoDeletedEvent.class));
        } else {
            log.debug("Ignoring video eventType={}", eventType);
        }
    }

    private void handlePublished(VideoPublishedEvent event) {
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

        markProcessed(event.eventId(), VIDEO_PUBLISHED);
    }

    /**
     * The document goes, rather than gaining a deleted flag: search has no use for a video nobody
     * can open, and every query would then have to remember to exclude it. Deleting an id that is
     * not there is a no-op in Elasticsearch, so a redelivery costs a round trip and nothing else —
     * the processed-events record is kept for consistency with the publication path, not because
     * this needs it.
     */
    private void handleDeleted(VideoDeletedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        videoDocumentRepository.deleteById(event.videoId());
        markProcessed(event.eventId(), VIDEO_DELETED);
    }

    private void markProcessed(String eventId, String eventType) {
        processedEventRepository.save(ProcessedEventDocument.builder()
                .id(eventId)
                .eventType(eventType)
                .processedAt(Instant.now())
                .build());
    }
}
