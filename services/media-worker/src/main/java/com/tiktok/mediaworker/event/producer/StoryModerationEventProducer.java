package com.tiktok.mediaworker.event.producer;

import com.tiktok.event.story.StoryModerationCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The only way a story's verdict reaches story-service. Waits for the ack for the reason
 * {@link VideoModerationEventProducer} does: this worker keeps no state, so a verdict the broker
 * never took exists nowhere — waiting turns that into a redelivery of the created event, which
 * scores the same file again. Keyed by storyId.
 */
@Component
@RequiredArgsConstructor
public class StoryModerationEventProducer {

    private static final String TOPIC = "media.story-moderation-events";

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publish(StoryModerationCompletedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.storyId(), objectMapper.writeValueAsString(event))
                    .get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing the moderation verdict for story " + event.storyId(), e);
        }
    }
}
