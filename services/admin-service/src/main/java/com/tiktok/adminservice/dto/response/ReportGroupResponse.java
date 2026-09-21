package com.tiktok.adminservice.dto.response;

import com.tiktok.adminservice.entity.ReportTargetType;

import java.time.Instant;

/**
 * One row of the moderation queue: every standing report against one target, collapsed into the
 * single decision an admin has to make about it.
 *
 * <p>Fifty people reporting one video is one row, not fifty — and resolving it closes all fifty,
 * because one decision answers all of them.
 */
public record ReportGroupResponse(
        ReportTargetType targetType,
        String targetId,
        long reportCount,
        Instant firstReportedAt,
        Instant lastReportedAt,
        /** The newest report's scenario label, as the reporter picked it. */
        String latestReason,
        /** The heaviest scenario reported against this target, 1–10. */
        int severity,
        /** {@code reportCount × severity} — what the queue is ordered by. */
        long priority
) {
}
