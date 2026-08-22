package com.tiktok.interactionservice.dto.response;

/**
 * @param counted false when this playId was already counted and the request changed nothing —
 *                a retry, not a replay. Returned rather than hidden so a client can tell "your
 *                view was recorded" from "that playback already counted" without guessing from
 *                the number.
 */
public record ViewResponse(
        Long videoId,
        boolean counted,
        long viewCount
) {
}
