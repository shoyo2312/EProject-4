package com.tiktok.interactionservice.dto.response;

/**
 * @param counted false when this view fell inside the caller's dedup window and changed nothing.
 *                Returned rather than hidden so a client can tell "your view was recorded" from
 *                "you already counted for this video today" without guessing from the number.
 */
public record ViewResponse(
        Long videoId,
        boolean counted,
        long viewCount
) {
}
