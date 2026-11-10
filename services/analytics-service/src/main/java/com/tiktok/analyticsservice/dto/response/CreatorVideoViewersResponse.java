package com.tiktok.analyticsservice.dto.response;

import java.util.Map;

/**
 * Who watched one video in the window. The breakdowns are withheld until the audience is large
 * enough (the same threshold as {@link CreatorViewersResponse}): with a handful of viewers,
 * "1 follower, 2 aged 13-17" comes close to naming them. The four are null when {@code
 * breakdownAvailable} is false.
 *
 * @param newViewers   viewers who had never watched this creator and came in through this video
 * @param returning    {@code total - newViewers}
 * @param followers    viewers following the creator at the window's end
 * @param nonFollowers {@code total - followers}
 * @param ageGroups    viewers by age at their first watch in the window; always all seven keys,
 *                     {@code 13-17, 18-24, 25-34, 35-44, 45-54, 55+, UNKNOWN}, in that order.
 *                     UNKNOWN is a viewer who has not given a birth date
 */
public record CreatorVideoViewersResponse(
        long total,
        long newViewers,
        long previousTotal,
        long previousNewViewers,
        boolean breakdownAvailable,
        Long returning,
        Long followers,
        Long nonFollowers,
        Map<String, Long> ageGroups
) {
}
