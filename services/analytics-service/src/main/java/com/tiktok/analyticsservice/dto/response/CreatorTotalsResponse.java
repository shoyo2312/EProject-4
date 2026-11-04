package com.tiktok.analyticsservice.dto.response;

/**
 * A window's totals. Likes and comments are net — an unlike or a deleted comment in the window takes
 * one back — so a quiet day can come out negative, as it does on TikTok.
 */
public record CreatorTotalsResponse(
        long views,
        long likes,
        long comments,
        long shares,
        long netFollowers
) {
}
