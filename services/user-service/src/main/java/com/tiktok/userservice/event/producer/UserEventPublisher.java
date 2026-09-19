package com.tiktok.userservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

/**
 * Writes the follow announcement to the outbox in the caller's transaction; {@link OutboxPublisher}
 * sends it to Kafka afterwards.
 *
 * <p>It used to send straight to the broker and block on the ack, inside the {@code @Transactional}
 * follow. That held a pooled connection for the whole round trip, and a broker that was down failed
 * a follow the database had already accepted — an event lost or a request lost, depending on which
 * side of the commit the failure landed. A row in the same transaction has neither problem: it
 * commits with the edge, and a broker outage only delays delivery.
 */
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    private static final String AGGREGATE_TYPE = "USER_FOLLOW";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publishFollowChanged(Long followerId, Long followingId, boolean followed) {
        UserFollowChangedEvent event = UserFollowChangedEvent.of(followerId, followingId, followed);

        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                // The record key downstream, so every change to one user's follower count keeps
                // its order on a single partition.
                .aggregateId(String.valueOf(followingId))
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build());
    }
}
