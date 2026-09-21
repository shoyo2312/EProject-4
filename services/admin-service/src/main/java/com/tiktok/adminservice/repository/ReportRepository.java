package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByIdAndDeletedAtIsNull(Long id);

    /** The reporter's standing report against this target, if they already filed one. */
    Optional<Report> findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
            Long reporterId, ReportTargetType targetType, String targetId);

    Page<Report> findByStatusAndDeletedAtIsNull(ReportStatus status, Pageable pageable);

    Page<Report> findByTargetTypeAndDeletedAtIsNull(ReportTargetType targetType, Pageable pageable);

    Page<Report> findByStatusAndTargetTypeAndDeletedAtIsNull(ReportStatus status, ReportTargetType targetType, Pageable pageable);

    Page<Report> findByDeletedAtIsNull(Pageable pageable);

    long countByStatusAndDeletedAtIsNull(ReportStatus status);

    /** How many reports have been filed against one target — shown on its console row. */
    long countByTargetTypeAndTargetIdAndDeletedAtIsNull(ReportTargetType targetType, String targetId);

    /**
     * The work queue: one row per reported target, heaviest first.
     *
     * <p>Ordered by {@code reportCount × severity} so that neither half can carry a row alone —
     * a hundred spam reports do not outrank one self-harm report, and one stray report on a
     * serious scenario does not outrank a target fifty people flagged. Ties go to whoever has
     * been waiting longest, which is the only thing that stops a mid-priority target from being
     * pushed down forever by newer arrivals.
     *
     * <p>Native, and grouped in a subquery, because the weight is wanted once per report and the
     * aggregate cannot refer to a select-list alias. Aliases are quoted: Postgres folds unquoted
     * ones to lower case and {@link ReportQueueRow} would then bind nothing.
     *
     * <p>A scenario {@code report_reason_weights} has never heard of — an older client's label,
     * or a reason typed by hand — weighs 2. Mid-table rather than 1 deliberately: an unrecognised
     * label is unknown, not harmless, and sorting it to the bottom is how a real report stops
     * being seen.
     */
    @Query(value = """
            SELECT r.target_type              AS "targetType",
                   r.target_id                AS "targetId",
                   COUNT(*)                   AS "reportCount",
                   MIN(r.created_at)          AS "firstReportedAt",
                   MAX(r.created_at)          AS "lastReportedAt",
                   (array_agg(r.reason ORDER BY r.id DESC))[1] AS "latestReason",
                   MAX(r.weight)              AS "severity",
                   COUNT(*) * MAX(r.weight)   AS "priority"
            FROM (SELECT rp.id, rp.target_type, rp.target_id, rp.reason, rp.created_at,
                         COALESCE(w.weight, 2) AS weight
                  FROM reports rp
                  LEFT JOIN report_reason_weights w ON w.reason = rp.reason
                  WHERE rp.deleted_at IS NULL AND rp.status = 'PENDING') r
            GROUP BY r.target_type, r.target_id
            ORDER BY "priority" DESC, "firstReportedAt" ASC
            """,
            countQuery = """
                    SELECT COUNT(*) FROM (
                        SELECT 1 FROM reports
                        WHERE deleted_at IS NULL AND status = 'PENDING'
                        GROUP BY target_type, target_id) g
                    """,
            nativeQuery = true)
    Page<ReportQueueRow> findPendingQueue(Pageable pageable);
}
