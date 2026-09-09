package com.tiktok.mediaworker.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The only way a moderation verdict reaches video-service.
 *
 * <p>Same shape as {@link VideoTranscodedEventProducer} and for the same reason: this service
 * keeps no state, so a record the broker never took is a verdict that exists nowhere and a video
 * parked at PENDING_MODERATION forever. Waiting for the ack turns that into a redelivery of the
 * transcode event, which re-runs the check and produces the same verdict.
 *
 * <p>Keyed by videoId, so one video's verdicts stay ordered against each other on redelivery.
 */
@Component
@RequiredArgsConstructor
public class VideoModerationEventProducer {

    private static final String TOPIC = "media.video-moderation-events";

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publish(VideoModerationCompletedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.videoId(), objectMapper.writeValueAsString(event))
                    .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing the moderation verdict for " + event.videoId(), e);
        }
    }
}
