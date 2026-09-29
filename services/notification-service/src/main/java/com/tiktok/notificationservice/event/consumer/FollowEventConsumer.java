package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The one stream that needs no lookup: a follow event names both accounts, so the recipient is
 * already in the payload.
 */
@Component
@RequiredArgsConstructor
public class FollowEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.follow-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserFollowChangedEvent event = objectMapper.readValue(payload, UserFollowChangedEvent.class);

        // An unfollow is not announced, same as an unlike.
        if (!event.followed() || event.followerId().equals(event.followingId())) {
            return;
        }

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () -> {
            if (event.requestApproved()) {
                // The owner approved it themselves, so they are not told; the requester is.
                notificationService.create(
                        event.followerId(),
                        event.followingId(),
                        NotificationType.FOLLOW_REQUEST_ACCEPTED,
                        "Follow request accepted",
                        "Your follow request was accepted.",
                        String.valueOf(event.followingId()));
                return;
            }

            notificationService.create(
                    event.followingId(),
                    event.followerId(),
                    NotificationType.NEW_FOLLOWER,
                    "New follower",
                    "Someone started following you.",
                    // The follower, not a video: this is the one type whose deep link goes to
                    // a profile.
                    String.valueOf(event.followerId()));
        });
    }
}
