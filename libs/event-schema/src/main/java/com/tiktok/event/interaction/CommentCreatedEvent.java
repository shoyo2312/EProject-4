package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code parentId} names the top-level comment a reply hangs under, already flattened by
 * interaction-service, and is null for a top-level comment. {@code replyToUserId} is the author
 * of the reply being answered, set only when the target was itself a reply. Without these two a
 * consumer rendering the comment live cannot tell a reply from a new thread.
 *
 * <p>{@code parentAuthorId} is the author of the comment the reply was written under, set for every
 * reply — unlike {@code replyToUserId}, which stays null for a reply to a top-level comment. It is
 * who gets told about the reply.
 */
public record CommentCreatedEvent(
        String eventId,
        Instant occurredAt,
        Long commentId,
        Long videoId,
        Long userId,
        String content,
        Long parentId,
        Long replyToUserId,
        Long parentAuthorId
) implements DomainEvent {

    public static CommentCreatedEvent of(Long commentId, Long videoId, Long userId, String content) {
        return of(commentId, videoId, userId, content, null, null, null);
    }

    public static CommentCreatedEvent of(Long commentId, Long videoId, Long userId, String content,
                                         Long parentId, Long replyToUserId) {
        return of(commentId, videoId, userId, content, parentId, replyToUserId, null);
    }

    public static CommentCreatedEvent of(Long commentId, Long videoId, Long userId, String content,
                                         Long parentId, Long replyToUserId, Long parentAuthorId) {
        return new CommentCreatedEvent(UUID.randomUUID().toString(), Instant.now(), commentId, videoId,
                userId, content, parentId, replyToUserId, parentAuthorId);
    }
}
