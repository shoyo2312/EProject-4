package com.tiktok.interactionservice.dto.response;

import java.time.Instant;

/**
 * A comment as the moderation console sees it. Unlike {@link CommentResponse} it carries
 * {@code deletedAt}: an admin reviewing a thread needs to see what has already been removed,
 * otherwise a comment somebody else took down is indistinguishable from one that never existed.
 * There is no {@code likedByMe} — nobody is signed in as a viewer here.
 */
public record AdminCommentResponse(
        Long commentId,
        Long videoId,
        Long userId,
        String content,
        Long parentId,
        Long replyToUserId,
        int likeCount,
        Instant createdAt,
        Instant deletedAt
) {
}
