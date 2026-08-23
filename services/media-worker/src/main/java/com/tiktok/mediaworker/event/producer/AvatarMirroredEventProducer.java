package com.tiktok.mediaworker.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.AvatarMirroredEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The only way a finished avatar copy reaches user-service.
 *
 * <p>Waits for the broker's ack for the same reason {@link VideoTranscodedEventProducer} does: this
 * service keeps no state, so a record the broker refuses leaves an object sitting in the bucket
 * that no profile will ever point at. Throwing instead redelivers the announcement that started
 * the copy, and mirroring again is the no-op that republishes this.
 */
@Component
@RequiredArgsConstructor
public class AvatarMirroredEventProducer {

    private static final String TOPIC = "media.avatar-events";

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publish(AvatarMirroredEvent event) {
        try {
            kafkaTemplate.send(TOPIC, String.valueOf(event.userId()), objectMapper.writeValueAsString(event))
                    .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted publishing the mirrored avatar of user " + event.userId(), e);
        }
    }
}
