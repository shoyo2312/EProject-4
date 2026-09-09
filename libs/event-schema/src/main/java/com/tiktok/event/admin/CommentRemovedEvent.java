package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin removing somebody else's comment. Carries both ids because a comment in Cassandra is
 * identified by the pair — {@code comments_by_video} is partitioned by video, and there is no way
 * to reach a row from the comment id alone.
 */
public record CommentRemovedEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long commentId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static CommentRemovedEvent of(Long videoId, Long commentId, Long adminId, String reason) {
        return new CommentRemovedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, commentId, adminId, reason);
    }
}
