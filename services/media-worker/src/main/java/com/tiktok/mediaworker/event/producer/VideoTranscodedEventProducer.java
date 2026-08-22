package com.tiktok.mediaworker.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoTranscodedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The only way a finished transcode reaches video-service.
 *
 * <p>This service keeps no state of its own — no outbox table, no record that a video was ever
 * transcoded. So the send has to be confirmed here or not at all: {@code KafkaTemplate.send()}
 * returns a future and throws synchronously only on a serialization error or a full buffer, which
 * means a broker that refuses the record leaves the video at PROCESSING with nothing anywhere
 * that would ever retry it. Waiting for the ack and throwing instead turns that into a redelivery
 * of the VideoPublishedEvent, and re-transcoding writes the same objects to the same keys.
 */
@Component
@RequiredArgsConstructor
public class VideoTranscodedEventProducer {

    private static final String TOPIC = "media.video-transcoded-events";

    /** Long enough to ride out a leader election, short enough not to hold the partition. */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publish(VideoTranscodedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.videoId(), objectMapper.writeValueAsString(event))
                    .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing the transcode result for " + event.videoId(), e);
        }
    }
}
