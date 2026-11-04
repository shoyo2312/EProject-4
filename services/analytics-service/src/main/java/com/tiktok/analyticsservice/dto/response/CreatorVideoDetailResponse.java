package com.tiktok.analyticsservice.dto.response;

import java.util.List;

/**
 * One video's analytics. Totals are lifetime; {@code daily} covers the window asked for.
 *
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
        List<Double> retention,
        List<CreatorDailyStatsResponse> daily
) {
}
