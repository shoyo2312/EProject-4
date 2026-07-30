package com.tiktok.analyticsservice.dto.response;

public record VideoEngagementSummaryResponse(
        String videoId,
        long likes,
        long comments,
        long shares
) {
}
