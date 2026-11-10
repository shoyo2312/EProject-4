package com.tiktok.analyticsservice.dto.response;

/**
 * A window's totals. Likes, comments and saves are net — an unlike, a deleted comment or an unsave in
 * the window takes one back — so a quiet day can come out negative, as it does on TikTok.
 */
public record CreatorTotalsResponse(
        long views,
        long likes,
        long comments,
        long shares,
        long saves,
        long netFollowers
) {
}
