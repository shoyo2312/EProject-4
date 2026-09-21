package com.tiktok.adminservice.repository;

import java.time.Instant;

/**
 * One target's standing reports, collapsed into the row the console queue shows. A projection
 * rather than a list of {@link com.tiktok.adminservice.entity.Report}: the queue never needs the
 * individual reports, and pulling a hundred rows to render one line is the thing the grouping
 * exists to avoid.
 *
 * <p>Spring Data binds these accessors to the native query's column labels, which are quoted in
 * {@link ReportRepository#findPendingQueue} so Postgres does not fold them to lower case.
 */
public interface ReportQueueRow {

    String getTargetType();

    String getTargetId();

    long getReportCount();

    /** When the first standing report arrived — the queue's tie-break, so nothing waits forever. */
    Instant getFirstReportedAt();

    Instant getLastReportedAt();

    /** The newest report's scenario label; what the row shows without expanding. */
    String getLatestReason();

    /** The heaviest scenario reported against this target, 1–10. */
    int getSeverity();

    /** {@code reportCount × severity} — what the queue is ordered by. */
    long getPriority();
}
