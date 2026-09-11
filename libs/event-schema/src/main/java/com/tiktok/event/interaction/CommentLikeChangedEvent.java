package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A like or unlike on a comment, carrying the tally interaction-service settled on so a viewer
 * elsewhere renders the same number without asking for it.
 *
 * <p>On its own topic, not {@code interaction.comment-events}: the consumers of that topic route
 * an unrecognised {@code eventType} to CommentCreatedEvent (see video-service's
 * CommentEventConsumer), so a third shape there would silently inflate every video's comment
 * count.
 */
public record CommentLikeChangedEvent(
        String eventId,
        Instant occurredAt,
        Long commentId,
        Long videoId,
        Long userId,
        boolean liked,
        int likeCount
) implements DomainEvent {

    public static CommentLikeChangedEvent of(Long commentId, Long videoId, Long userId,
                                             boolean liked, int likeCount) {
        return new CommentLikeChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                commentId, videoId, userId, liked, likeCount);
    }
}
