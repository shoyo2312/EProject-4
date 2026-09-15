package com.tiktok.userservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserFollowChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes right after the follow-edge write commits. Confirmed, same reasoning as
 * InteractionEventPublisher's counter-bearing sends: chat-service's realtime fan-out is the only
 * consumer today, but a lost record there is a follower/following count stuck stale until the
 * next follow anywhere touches that pair of ids — worth a synchronous ack wait, not worth failing
 * the request over (see {@link #publish}).
 */
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);
    private static final String FOLLOW_TOPIC = "user.follow-events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publishFollowChanged(Long followerId, Long followingId, boolean followed) {
        UserFollowChangedEvent event = UserFollowChangedEvent.of(followerId, followingId, followed);
        ProducerRecord<String, String> record = new ProducerRecord<>(
                FOLLOW_TOPIC, String.valueOf(followingId), objectMapper.writeValueAsString(event));
        try {
            kafkaTemplate.send(record).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to " + FOLLOW_TOPIC, e);
        }
    }
}
