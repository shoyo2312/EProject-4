package com.tiktok.analyticsservice.dto.response;

import java.time.LocalDate;

/**
 * One day of a creator's chart, in Vietnam's calendar. Every day of the window is present, zeros
 * included, so a client can draw it without filling gaps. Follower moves are the account's, and 0 on
 * a single video's chart.
 */
public record CreatorDailyStatsResponse(
        LocalDate day,
        long views,
        long likes,
        long comments,
        long shares,
        long followersGained,
        long followersLost
) {
}
