package com.tiktok.analyticsservice.dto.response;

import java.time.LocalDate;

/**
 * @param activeUsers distinct accounts that watched something that day. Watching is the one
 *                    action every session produces, which is what makes it the honest measure of
 *                    "used the app" — likes and comments would count only the people who posted.
 */
public record DailyActiveUsersResponse(
        LocalDate day,
        long activeUsers
) {
}
