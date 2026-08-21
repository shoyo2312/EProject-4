package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoTranscodedEventProducer;
import com.tiktok.mediaworker.service.MediaCleanupService;
import com.tiktok.mediaworker.service.TranscodeResult;
import com.tiktok.mediaworker.service.TranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * video.video-events carries a publication and a deletion, both flat JSON objects with no type
 * field, so routing is on the eventType header video-service sets — see its VideoEventPublisher.
 *
 * <p>No inbox/idempotency table here: media-worker keeps no state of its own. Re-processing
 * the same VideoPublishedEvent just overwrites the same MinIO keys with the same content
 * (safe no-op), re-processing a deletion removes objects that are already gone, and the durable
 * mutation the transcode triggers happens in video-service's own inbox-guarded consumer of
 * VideoTranscodedEvent — that's where duplicate delivery actually needs to be rejected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoEventConsumer {

    private static final String VIDEO_PUBLISHED = "VideoPublishedEvent";
    private static final String VIDEO_DELETED = "VideoDeletedEvent";

    private final TranscodeService transcodeService;
    private final MediaCleanupService mediaCleanupService;
    private final VideoTranscodedEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "media-worker")
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
            VideoDeletedEvent event = objectMapper.readValue(payload, VideoDeletedEvent.class);
            mediaCleanupService.deleteMediaFor(event.videoId(), event.rawFileUrl());
        } else {
            log.debug("Ignoring video eventType={}", eventType);
        }
    }

    private void handlePublished(VideoPublishedEvent event) {
        try {
            TranscodeResult result = transcodeService.transcode(event.videoId(), event.rawFileUrl());
            eventProducer.publish(VideoTranscodedEvent.success(
                    event.videoId(), result.thumbnailUrl(), result.hlsUrl(), result.durationSeconds()));
        } catch (Exception e) {
            log.error("Transcode failed for video {}", event.videoId(), e);
            eventProducer.publish(VideoTranscodedEvent.failure(event.videoId(), e.getMessage()));
        }
    }
}
