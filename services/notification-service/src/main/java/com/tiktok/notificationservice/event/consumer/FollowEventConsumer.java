package com.tiktok.notificationservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.notificationservice.client.BlockClient;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A follow event names both accounts, so the recipient is already in the payload; the only lookup
 * is the block check every notification here goes through.
 */
@Component
@RequiredArgsConstructor
public class FollowEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final BlockClient blockClient;

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
                notify(
                        event.followerId(),
                        event.followingId(),
                        NotificationType.FOLLOW_REQUEST_ACCEPTED,
                        "Follow request accepted",
                        "Your follow request was accepted.",
                        String.valueOf(event.followingId()));
                return;
            }

            notify(
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

    /**
     * Not between accounts that have since blocked one another. user-service refuses a follow
     * across a block and a block severs the follow, but this event left before either could stop
     * it: a block placed while it waited in the outbox or the topic would otherwise still land the
     * blocked account in the blocker's inbox.
     */
    private void notify(Long recipientId, Long actorId, NotificationType type,
                        String title, String body, String referenceId) {
        if (blockClient.isBlockedBetween(recipientId, actorId)) {
            return;
        }
        notificationService.create(recipientId, actorId, type, title, body, referenceId);
    }
}
