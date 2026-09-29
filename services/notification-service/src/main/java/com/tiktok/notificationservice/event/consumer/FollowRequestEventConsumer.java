package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.FollowRequestedEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Tells the owner of a private account that someone asked to follow them. Like a follow, the event
 * names both accounts, so there is nothing to look up.
 *
 * <p>ponytail: a request that is withdrawn or rejected leaves this notification in the inbox. The
 * requests list is the authority; retracting it would need a delete path this service does not
 * have.
 */
@Component
@RequiredArgsConstructor
public class FollowRequestEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.follow-request-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload) {
        FollowRequestedEvent event = objectMapper.readValue(payload, FollowRequestedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notificationService.create(
                        event.targetId(),
                        event.requesterId(),
                        NotificationType.FOLLOW_REQUEST,
                        "Follow request",
                        "Someone asked to follow you.",
                        // The requester's profile, like NEW_FOLLOWER: where the owner goes to decide.
                        String.valueOf(event.requesterId())));
    }
}
