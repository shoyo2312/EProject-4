package com.tiktok.adminservice.dto.response;

import java.time.LocalDate;

/**
 * One day's moderation flow — the series every period-over-period delta in the console is
 * computed from.
 *
 * <p>Two clocks, deliberately. {@code reportsCreated} and everything under
 * {@code actionsTaken} are stamped when the row was written; {@code reportsResolved} and
 * {@code reportsDismissed} are stamped when the report was <em>closed</em>, not when it was
 * filed. A report filed in March and dismissed in September belongs to September's dismissals
 * — dating it by {@code created_at} would credit the work to the month nobody did it in.
 */
public record DailyAdminStatsResponse(
        LocalDate day,
        long reportsCreated,
        long actionsTaken,
        long reportsResolved,
        long reportsDismissed,
        long usersBanned,
        long videosTakenDown,
        long commentsRemoved
) {
}
