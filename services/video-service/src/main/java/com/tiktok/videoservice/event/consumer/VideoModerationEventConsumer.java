package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.videoservice.entity.VideoModeration;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns media-worker's automatic moderation verdict into the video's status.
 *
 * <p>This is the only thing that moves a video off PENDING_MODERATION, which is why media-worker
 * always publishes a verdict even when the classifier could not be reached — an unreachable
 * classifier arrives here as REVIEW and lands the video in the admin queue, rather than as
 * nothing at all and a video parked forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoModerationEventConsumer {

    private static final String EVENT = "VideoModerationCompletedEvent";

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final VideoStateUpdater videoStateUpdater;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-moderation-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoModerationCompletedEvent event = objectMapper.readValue(payload, VideoModerationCompletedEvent.class);

        idempotentEventProcessor.runOnce(
                event.eventId(), event.getClass().getSimpleName(), () -> apply(event));
    }

    private void apply(VideoModerationCompletedEvent event) {
        log.info("Moderation verdict {} for videoId={} ({} {} over {} frames)",
                event.verdict(), event.videoId(), event.label(), event.maxScore(), event.totalFrames());

        VideoModeration moderation = VideoModeration.builder()
                .verdict(event.verdict())
                .label(event.label())
                .maxScore(event.maxScore())
                .suspiciousFrames(event.suspiciousFrames())
                .totalFrames(event.totalFrames())
                .model(event.model())
                .modelVersion(event.modelVersion())
                .reason(event.reason())
                .checkedAt(event.occurredAt())
                .build();

        // Deleted videos and takedowns landing mid-check are both handled by the updater; see
        // VideoStateUpdater for why the outcome is re-read rather than written twice.
        videoStateUpdater.apply(event.videoId(),
                video -> video.applyModeration(moderation),
                videoRepository::updateModeration,
                EVENT);
    }
}
