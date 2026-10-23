package com.tiktok.analyticsservice.dto.response;

import java.util.List;

/**
 * Studio's key metrics. {@code previous} is the window of the same length just before this one —
 * what the "(+12%)" under each figure compares against.
 */
public record CreatorOverviewResponse(
        CreatorTotalsResponse current,
        CreatorTotalsResponse previous,
        List<CreatorDailyStatsResponse> daily
) {
}
