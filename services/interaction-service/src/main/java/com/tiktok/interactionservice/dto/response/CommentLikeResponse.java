package com.tiktok.interactionservice.dto.response;

/** Result of liking or unliking a comment: the new state plus the denormalised tally. */
public record CommentLikeResponse(
        Long commentId,
        boolean liked,
        int likeCount
) {
}
