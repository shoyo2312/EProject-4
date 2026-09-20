package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.notificationservice.client.VideoOwnerClient;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Turns the three interaction streams into inbox entries for the account that owns the video.
 *
 * <p>None of these events names that account — they carry the video and whoever acted on it — so
 * the owner is resolved through {@link VideoOwnerClient}. An unresolvable owner drops the
 * notification rather than failing the delivery; see that class for why.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionEventConsumer {

    private static final String COMMENT_CREATED = "CommentCreatedEvent";

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final VideoOwnerClient videoOwnerClient;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.like-events", groupId = "notification-service")
    @SneakyThrows
    public void onLike(String payload) {
        VideoLikeEvent event = objectMapper.readValue(payload, VideoLikeEvent.class);

        // An unlike is not news. Nothing is removed either: the notification records that the
        // like happened, which stays true after the liker changes their mind.
        if (!event.liked()) {
            return;
        }

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notifyVideoOwner(event.videoId(), event.userId(), NotificationType.LIKE,
                        "Lượt thích mới", "Video của bạn vừa nhận được một lượt thích."));
    }

    /**
     * {@code interaction.comment-events} carries a creation and a deletion as flat JSON with no
     * type field, so routing is on the {@code eventType} header interaction-service sets. An
     * absent header means a producer older than the deletion event, when everything on this topic
     * was a creation. Without the header check a deletion would parse cleanly into
     * CommentCreatedEvent with null fields and announce a comment that was just removed.
     */
    @KafkaListener(topics = "interaction.comment-events", groupId = "notification-service")
    @SneakyThrows
    public void onComment(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null
                ? COMMENT_CREATED
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (!COMMENT_CREATED.equals(eventType)) {
            return;
        }

        CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () -> {
            // A reply is news for the person being replied to, not for the video's owner — who
            // already heard about the thread when it started. replyToUserId is only set when the
            // target was itself a reply, so a reply to a top-level comment still notifies the
            // owner: the top-level author's id is not in the event, and asking
            // interaction-service for it would add a second lookup to every comment.
            if (event.replyToUserId() != null) {
                notify(event.replyToUserId(), event.userId(), NotificationType.COMMENT,
                        "Phản hồi mới", event.content(),
                        String.valueOf(event.videoId()));
                return;
            }

            notifyVideoOwner(event.videoId(), event.userId(), NotificationType.COMMENT,
                    "Bình luận mới", event.content());
        });
    }

    @KafkaListener(topics = "interaction.share-events", groupId = "notification-service")
    @SneakyThrows
    public void onShare(String payload) {
        VideoSharedEvent event = objectMapper.readValue(payload, VideoSharedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notifyVideoOwner(event.videoId(), event.userId(), NotificationType.SHARE,
                        "Lượt chia sẻ mới", "Video của bạn vừa được chia sẻ."));
    }

    private void notifyVideoOwner(Long videoId, Long actorId, NotificationType type,
                                  String title, String body) {
        Long ownerId = videoOwnerClient.ownerOf(videoId);
        if (ownerId == null) {
            return;
        }
        notify(ownerId, actorId, type, title, body, String.valueOf(videoId));
    }

    /** referenceId is the video, which is what the client deep-links to for all three types. */
    private void notify(Long recipientId, Long actorId, NotificationType type,
                        String title, String body, String referenceId) {
        // Nobody wants to be told about their own like. Cheap to check and easy to forget, so it
        // lives on the one path every type goes through rather than in each listener.
        if (recipientId.equals(actorId)) {
            return;
        }
        notificationService.create(recipientId, actorId, type, title, body, referenceId);
    }
}
