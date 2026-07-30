package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoTranscodedEventProducer;
import com.tiktok.mediaworker.service.TranscodeResult;
import com.tiktok.mediaworker.service.TranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * No inbox/idempotency table here: media-worker keeps no state of its own. Re-processing
 * the same VideoPublishedEvent just overwrites the same MinIO keys with the same content
 * (safe no-op), and the durable mutation this triggers happens in video-service's own
 * inbox-guarded consumer of VideoTranscodedEvent — that's where duplicate delivery
 * actually needs to be rejected.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoPublishedEventConsumer {

    private final TranscodeService transcodeService;
    private final VideoTranscodedEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "media-worker")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoPublishedEvent event = objectMapper.readValue(payload, VideoPublishedEvent.class);

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
