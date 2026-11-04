package com.tiktok.analyticsservice.dto.response;

import java.util.List;

/**
 * Who is watching. The breakdowns are withheld until the audience is large enough: with a handful of
 * viewers, "most active at 23:00" and "2 new viewers" come close to naming them. {@code
 * breakdownAvailable} says which case this is, and the lists are null when it is false.
 *
 * @param activeHours distinct viewers by hour of the day, 24 entries from 00:00, Vietnam time
 * @param activeDays  distinct viewers by day of the week, 7 entries from Monday
 */
public record CreatorViewersResponse(
        long totalViewers,
        long newViewers,
        long previousTotalViewers,
        long previousNewViewers,
        boolean breakdownAvailable,
        List<Long> activeHours,
        List<Long> activeDays
) {
}
