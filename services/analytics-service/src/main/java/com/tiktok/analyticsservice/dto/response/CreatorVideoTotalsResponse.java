package com.tiktok.analyticsservice.dto.response;

/**
 * One video's numbers over a window. Likes, comments and saves are net; {@code newFollowers} is the
 * follows that came from the video, gross.
 */
public record CreatorVideoTotalsResponse(
        long views,
        long likes,
        long comments,
        long shares,
        long saves,
        long newFollowers
) {
}
