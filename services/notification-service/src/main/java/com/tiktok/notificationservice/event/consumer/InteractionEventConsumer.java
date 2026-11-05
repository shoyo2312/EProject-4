package com.tiktok.notificationservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentLikeChangedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoRepostedEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.notificationservice.client.BlockClient;
import com.tiktok.notificationservice.client.UserHandleClient;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Turns the interaction streams into inbox entries for the account that owns the video.
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

    /** What tells a mention from a comment on the owner's video; the web matches it exactly. */
    static final String MENTION_TITLE = "New mention";

    private static final String MENTION_EVENT_TYPE = "CommentMention";

    /**
     * Older than this, a repost is not news. This group first met {@code interaction.repost-events}
     * with no committed offset, and reads from the earliest one, so without a cutoff that first start
     * announced — and pushed — every repost still retained (7 days) as if it had just happened. The
     * same cutoff covers a DLT replay or a long outage's backlog.
     */
    static final Duration MAX_REPOST_AGE = Duration.ofHours(24);

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final VideoOwnerClient videoOwnerClient;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final BlockClient blockClient;
    private final UserHandleClient userHandleClient;

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
                        "New like", "Someone liked your video."));
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
        // A reply is news for the person being replied to, not for the video's owner — who
        // already heard about the thread when it started. replyToUserId covers events from a
        // producer older than parentAuthorId, which only knew the author of a nested target.
        Long repliedTo = event.parentAuthorId() != null ? event.parentAuthorId() : event.replyToUserId();

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () -> {
            if (repliedTo != null) {
                notify(repliedTo, event.userId(), NotificationType.COMMENT,
                        "New reply", event.content(),
                        String.valueOf(event.videoId()));
                return;
            }

            notifyVideoOwner(event.videoId(), event.userId(), NotificationType.COMMENT,
                    "New comment", event.content());
        });

        notifyMentioned(event, repliedTo);
    }

    /**
     * Everyone the comment tags, as a {@code COMMENT} titled {@link #MENTION_TITLE} — not a type of
     * its own: the mobile app parses the type into an enum with no fallback and would fail its
     * whole inbox. The web keys "mentioned you in a comment" on the title, as it keys the
     * new-device notice on SYSTEM's reference.
     *
     * <p>One claim per recipient, after the comment's own: a lookup that fails redelivers the
     * event, and the claims already taken keep anyone from hearing it twice. Whoever the comment
     * itself was addressed to already heard about it.
     */
    private void notifyMentioned(CommentCreatedEvent event, Long repliedTo) {
        List<String> handles = CommentMentions.handlesIn(event.content());
        if (handles.isEmpty()) {
            return;
        }
        Collection<Long> tagged = userHandleClient.idsByUsername(handles).values();
        if (tagged.isEmpty()) {
            return;
        }
        Long alreadyTold = repliedTo != null ? repliedTo : videoOwnerClient.ownerOf(String.valueOf(event.videoId()));
        for (Long recipientId : Set.copyOf(tagged)) {
            if (recipientId.equals(alreadyTold)) {
                continue;
            }
            idempotentEventProcessor.runOnce(event.eventId() + ":mention:" + recipientId, MENTION_EVENT_TYPE, () ->
                    notify(recipientId, event.userId(), NotificationType.COMMENT, MENTION_TITLE,
                            event.content(), String.valueOf(event.videoId())));
        }
    }

    /**
     * Only a like is news, as with videos. An event without commentAuthorId came from a producer
     * older than the field and has nobody to tell.
     */
    @KafkaListener(topics = "interaction.comment-like-events", groupId = "notification-service")
    @SneakyThrows
    public void onCommentLike(String payload) {
        CommentLikeChangedEvent event = objectMapper.readValue(payload, CommentLikeChangedEvent.class);
        if (!event.liked() || event.commentAuthorId() == null) {
            return;
        }

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notify(event.commentAuthorId(), event.userId(), NotificationType.COMMENT_LIKE,
                        "New like", "Someone liked your comment.",
                        String.valueOf(event.videoId())));
    }

    @KafkaListener(topics = "interaction.share-events", groupId = "notification-service")
    @SneakyThrows
    public void onShare(String payload) {
        VideoSharedEvent event = objectMapper.readValue(payload, VideoSharedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notifyVideoOwner(event.videoId(), event.userId(), NotificationType.SHARE,
                        "New share", "Someone shared your video."));
    }

    /**
     * Only a fresh repost is news; taking it back is not, and removes nothing — as with a like. See
     * {@link #MAX_REPOST_AGE} for what "fresh" guards against.
     */
    @KafkaListener(topics = "interaction.repost-events", groupId = "notification-service")
    @SneakyThrows
    public void onRepost(String payload) {
        VideoRepostedEvent event = objectMapper.readValue(payload, VideoRepostedEvent.class);
        if (!event.reposted()
                || event.occurredAt() != null && event.occurredAt().isBefore(Instant.now().minus(MAX_REPOST_AGE))) {
            return;
        }

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notifyVideoOwner(event.videoId(), event.userId(), NotificationType.REPOST,
                        "New repost", "Someone reposted your video."));
    }

    private void notifyVideoOwner(Long videoId, Long actorId, NotificationType type,
                                  String title, String body) {
        Long ownerId = videoOwnerClient.ownerOf(String.valueOf(videoId));
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
        // Nor from someone they are blocking, or blocked by. video-service already stops a blocked
        // account liking or commenting on the blocker's videos, but not replying to the blocker's
        // comment under a third party's video, or liking it — and this is the one path all of
        // those reach.
        if (blockClient.isBlockedBetween(recipientId, actorId)) {
            return;
        }
        notificationService.create(recipientId, actorId, type, title, body, referenceId);
    }
}
