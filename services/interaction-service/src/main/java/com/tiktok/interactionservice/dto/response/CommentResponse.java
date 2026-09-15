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
        /**
         * Set only when this reply targets another reply: the author of that reply, so the client
         * can render "A > B". Null otherwise.
         */
        Long replyToUserId,
        /** Denormalised like tally for this comment. */
        int likeCount,
        /** Whether the requesting user has liked it — always false for an anonymous listing. */
        boolean likedByMe,
        /**
         * Live replies hanging off this comment, for the "View N replies" button. Always 0 on a
         * reply: the thread is one level deep, so a reply never has replies of its own.
         */
        int replyCount
) {
    public CommentResponse withLikedByMe(boolean value) {
        return new CommentResponse(commentId, videoId, userId, content, createdAt, parentId,
                replyToUserId, likeCount, value, replyCount);
    }

    public CommentResponse withReplyCount(int value) {
        return new CommentResponse(commentId, videoId, userId, content, createdAt, parentId,
                replyToUserId, likeCount, likedByMe, value);
    }
}
