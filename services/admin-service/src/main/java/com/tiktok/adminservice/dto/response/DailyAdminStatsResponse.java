package com.tiktok.adminservice.dto.response;

import java.time.LocalDate;

/** One day's reports-filed and actions-taken counts — the series the dashboard deltas compare. */
public record DailyAdminStatsResponse(
        LocalDate day,
        long reportsCreated,
        long actionsTaken
) {
}
