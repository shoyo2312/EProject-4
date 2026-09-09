package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoModerationEventProducer;
import com.tiktok.mediaworker.service.ModerationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads this worker's own transcode results back off the topic and moderates what they produced.
 *
 * <p>A second consumer group on the same topic rather than a step at the end of the transcode:
 * transcoding and moderation fail independently, and running them on one thread means a
 * classifier that is down holds the partition that finished videos are trying to leave through.
 * Separate groups also mean the offsets move separately, so a moderation problem can be replayed
 * without re-encoding anything.
 *
 * <p>No inbox table — this worker keeps no state anywhere. A redelivered transcode event just
 * re-samples the same file and produces the same verdict; the durable write is video-service's,
 * and its consumer of VideoModerationCompletedEvent is where duplicates are actually rejected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoModerationConsumer {

    private final ModerationService moderationService;
    private final VideoModerationEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "media.video-transcoded-events", groupId = "media-worker-moderation")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoTranscodedEvent event = objectMapper.readValue(payload, VideoTranscodedEvent.class);

        // A video that could not be transcoded is FAILED and never reaches a viewer, so there is
        // nothing for moderation to protect anyone from — and no playback file to sample.
        if (!event.success()) {
            log.debug("Skipping moderation of failed video {}", event.videoId());
            return;
        }

        // moderate() never throws: an unreachable classifier comes back as a REVIEW verdict, not
        // as an exception, because a video with no verdict at all would sit at PENDING_MODERATION
        // with nothing left to move it.
        eventProducer.publish(moderationService.moderate(event.videoId(), event.durationSeconds()));
    }
}
