package com.tiktok.interactionservice.dto.response;

import java.time.Instant;

public record CommentResponse(
        Long commentId,
        Long videoId,
        Long userId,
        String content,
        Instant createdAt,
        /** Null for a top-level comment; the top-level comment's id for a reply. */
        Long parentId,
        /** Denormalised like tally for this comment. */
        int likeCount,
        /** Whether the requesting user has liked it — always false for an anonymous listing. */
        boolean likedByMe
) {
    public CommentResponse withLikedByMe(boolean value) {
        return new CommentResponse(commentId, videoId, userId, content, createdAt, parentId, likeCount, value);
    }
}
