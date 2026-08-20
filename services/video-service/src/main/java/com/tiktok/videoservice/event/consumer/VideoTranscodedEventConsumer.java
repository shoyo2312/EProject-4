package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.videoservice.entity.Video;
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

    private static final String EVENT = "VideoTranscodedEvent";

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final VideoStateUpdater videoStateUpdater;
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
            videoStateUpdater.apply(event.videoId(), Video::markFailed, videoRepository::updateStatus, EVENT);
            return;
        }

        // Deleted videos and takedowns landing mid-transcode are both handled by the updater; see
        // VideoStateUpdater for why the result is re-read rather than written twice.
        videoStateUpdater.apply(event.videoId(),
                video -> video.markPublished(event.thumbnailUrl(), event.hlsUrl(), event.durationSeconds()),
                videoRepository::updateTranscodeResult,
                EVENT);
    }
}
