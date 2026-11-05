package com.tiktok.analyticsservice.dto.response;

import java.util.List;

/**
 * One video's analytics. The top-level totals are lifetime; {@code current} is the window asked for,
 * {@code previous} the window of the same length just before it, and {@code daily} the window by day.
 *
 * @param newFollowers   follows that came from this video, gross, lifetime
 * @param avgWatchMs     time watched per view
 * @param completionRate share of views that reached the end, 0 to 1
 * @param retention      21 points, 0% to 100% of the video's length in steps of 5%: the share of
 *                       views still watching at that point. The first is always 1 when there are
 *                       views, and all are 0 when there are none
 */
public record CreatorVideoDetailResponse(
        String videoId,
        long views,
        long viewers,
        long totalWatchMs,
        long avgWatchMs,
        double completionRate,
        long likes,
        long comments,
        long shares,
        long saves,
        long newFollowers,
        List<Double> retention,
        List<CreatorDailyStatsResponse> daily,
        CreatorVideoTotalsResponse current,
        CreatorVideoTotalsResponse previous
) {
}
