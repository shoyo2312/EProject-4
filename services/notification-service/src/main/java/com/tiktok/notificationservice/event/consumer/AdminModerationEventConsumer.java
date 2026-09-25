package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserWarnedEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.notificationservice.client.CommentAuthorClient;
import com.tiktok.notificationservice.client.CommentAuthorClient.CommentTarget;
import com.tiktok.notificationservice.client.VideoOwnerClient;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Tells people what moderation did to them.
 *
 * <p>Every other consumer of admin.moderation-events changes platform state: auth-service flips
 * the account, video-service pulls the video, search drops it from the index. None of them says a
 * word to the person it happened to, who until now found out by trying to log in. That is also
 * what made WARN_USER pointless — a warning nobody receives is a row in an audit log.
 *
 * <p>Routing is by the eventType header, for the same reason as every other consumer of this
 * topic: UserBanned and UserUnbanned are the same JSON shape.
 *
 * <p>Only enforcement is announced. An unban or a restore is good news the person gets by
 * finding their account working again, and a notification for it is one more thing to get wrong
 * on a topic where a redelivery is routine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String USER_BANNED = "UserBannedEvent";
    private static final String VIDEO_TAKEN_DOWN = "VideoTakenDownEvent";
    private static final String USER_WARNED = "UserWarnedEvent";

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final VideoOwnerClient videoOwnerClient;
    private final CommentAuthorClient commentAuthorClient;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader);

        switch (eventType == null ? "" : eventType) {
            case USER_BANNED -> {
                UserBannedEvent event = objectMapper.readValue(payload, UserBannedEvent.class);
                notify(event.eventId(), USER_BANNED, event.userId(), "Your account was suspended",
                        body(event.reason(), "Your account is suspended."), null);
            }
            case VIDEO_TAKEN_DOWN -> {
                VideoTakenDownEvent event = objectMapper.readValue(payload, VideoTakenDownEvent.class);
                // The event names the video, never its owner — the same lookup the like and
                // comment consumers do, and it fails the same way: no owner, no notification.
                Long ownerId = videoOwnerClient.ownerOf(event.videoId());
                notify(event.eventId(), VIDEO_TAKEN_DOWN, ownerId, "Your video was removed",
                        body(event.reason(), "One of your videos was removed."), event.videoId());
            }
            case USER_WARNED -> {
                UserWarnedEvent event = objectMapper.readValue(payload, UserWarnedEvent.class);
                notify(event.eventId(), USER_WARNED, recipientOf(event), "A warning from moderation",
                        body(event.reason(), "An admin reviewed something you posted."),
                        referenceOf(event));
            }
            case "" -> log.warn("Moderation event without an eventType header, dropped: {}", payload);
            default -> log.debug("Ignoring moderation eventType={}", eventType);
        }
    }

    /**
     * Who a warning is addressed to. A warning may be filed over any kind of report, so the
     * target is whatever was reported: an account is already the person, a video is its owner,
     * and a comment is its author. The comment case used to answer nobody, so a warning over a
     * comment report — the commonest thing to warn over — reached only the audit log.
     */
    private Long recipientOf(UserWarnedEvent event) {
        return switch (event.targetType() == null ? "" : event.targetType()) {
            case "USER" -> Long.valueOf(event.targetId());
            case "VIDEO" -> videoOwnerClient.ownerOf(event.targetId());
            case "COMMENT" -> commentAuthorClient.authorOf(event.targetId());
            default -> {
                log.debug("No recipient for a warning against a {}", event.targetType());
                yield null;
            }
        };
    }

    /**
     * Where the notice opens: the video that was reported, or the one the reported comment is on.
     * A warning over an account has nowhere more specific to go.
     */
    private String referenceOf(UserWarnedEvent event) {
        return switch (event.targetType() == null ? "" : event.targetType()) {
            case "VIDEO" -> event.targetId();
            case "COMMENT" -> {
                CommentTarget comment = CommentTarget.parse(event.targetId());
                yield comment == null ? null : String.valueOf(comment.videoId());
            }
            default -> null;
        };
    }

    /** The admin's own words are the whole message; the fallback is for a reason left blank. */
    private String body(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private void notify(String eventId, String eventType, Long recipientId,
                        String title, String body, String referenceId) {
        if (recipientId == null) {
            return;
        }
        // actorId stays null: the recipient has no business being handed the admin's user id,
        // and SYSTEM is the one type the inbox does not render as "someone did this".
        idempotentEventProcessor.runOnce(eventId, eventType, () ->
                notificationService.create(recipientId, null, NotificationType.SYSTEM,
                        title, body, referenceId));
    }
}
