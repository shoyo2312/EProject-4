package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTranscodedEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-transcoded-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoTranscodedEvent event = objectMapper.readValue(payload, VideoTranscodedEvent.class);

        idempotentEventProcessor.runOnce(
                event.eventId(), event.getClass().getSimpleName(), () -> apply(event));
    }

    private void apply(VideoTranscodedEvent event) {
        if (!event.success()) {
            log.warn("VideoTranscodedEvent failure for videoId={}: {}", event.videoId(), event.failureReason());
        }

        // Deleted videos are skipped rather than updated. Transcoding takes far longer than the
        // window in which an owner can delete, so the event routinely comes back for a video that
        // is already gone; writing the result anyway leaves a document that is both soft-deleted
        // and PUBLISHED, a combination no read path expects and nothing later corrects.
        videoRepository.findByIdAndDeletedAtIsNull(event.videoId()).ifPresentOrElse(video -> {
            if (event.success()) {
                video.markPublished(event.thumbnailUrl(), event.hlsUrl(), event.durationSeconds());
                videoRepository.updateTranscodeResult(video);
            } else {
                video.markFailed();
                videoRepository.updateStatus(video);
            }
        }, () -> log.warn("VideoTranscodedEvent for unknown or deleted videoId={}", event.videoId()));
    }
}
