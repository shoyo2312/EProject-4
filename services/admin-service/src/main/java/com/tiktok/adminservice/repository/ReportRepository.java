package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /** Reports filed per day since the cutoff — the flow the dashboard's delta compares. */
    @Query(value = """
            SELECT date_trunc('day', created_at)::date AS "day", COUNT(*) AS "cnt"
            FROM reports
            WHERE deleted_at IS NULL AND created_at >= :since
            GROUP BY "day"
            ORDER BY "day"
            """, nativeQuery = true)
    List<DailyCountRow> findDailyCreated(@Param("since") Instant since);

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

    /**
     * Closes every standing report against one target, because one decision answers all of them.
     * {@code exceptId} is the report the caller has already resolved through JPA — updating it
     * again here would write over the entity's own pending change.
     *
     * <p>A bulk update rather than loading each report: a target can carry hundreds, and none of
     * their state matters beyond the columns being set. {@code update versioned} so the rows
     * still take a version bump — a bulk update goes round the entity, so Hibernate would
     * otherwise leave stale copies in other transactions passing their optimistic-lock check.
     */
    @Modifying
    @Query("""
            update versioned Report r
            set r.status = :status,
                r.resolvedBy = :adminId,
                r.resolvedAt = :now,
                r.updatedAt = :now
            where r.deletedAt is null
              and r.status = com.tiktok.adminservice.entity.ReportStatus.PENDING
              and r.targetType = :targetType
              and r.targetId = :targetId
              and (:exceptId is null or r.id <> :exceptId)
            """)
    int closePendingFor(@Param("targetType") ReportTargetType targetType,
                        @Param("targetId") String targetId,
                        @Param("status") ReportStatus status,
                        @Param("adminId") Long adminId,
                        @Param("exceptId") Long exceptId,
                        @Param("now") Instant now);

    /**
     * Auto-dismisses one batch of reports nobody got to: older than the cutoff, on a scenario
     * marked {@code auto_expire}, and against a target too lightly reported to be worth a
     * moderator's time. {@code resolved_by} stays NULL, which is how the console tells a decision
     * the service made from one an admin made.
     *
     * <p>The count is taken over the target's whole standing group, not the batch, so a target
     * that crossed the threshold last week is never swept on the strength of its oldest report
     * alone. Batched by id so a backlog cannot turn into one transaction holding every lock.
     */
    @Modifying
    @Query(value = """
            UPDATE reports
            SET status      = 'DISMISSED',
                resolved_at = NOW(),
                updated_at  = NOW(),
                version     = version + 1
            WHERE id IN (
                SELECT rp.id
                FROM reports rp
                LEFT JOIN report_reason_weights w ON w.reason = rp.reason
                JOIN (SELECT target_type, target_id
                      FROM reports
                      WHERE deleted_at IS NULL AND status = 'PENDING'
                      GROUP BY target_type, target_id
                      HAVING COUNT(*) <= :maxReportsPerTarget) q
                  ON q.target_type = rp.target_type AND q.target_id = rp.target_id
                WHERE rp.deleted_at IS NULL
                  AND rp.status = 'PENDING'
                  AND rp.created_at < :cutoff
                  AND COALESCE(w.auto_expire, TRUE)
                LIMIT :batchSize)
            """, nativeQuery = true)
    int dismissStale(@Param("cutoff") Instant cutoff,
                     @Param("maxReportsPerTarget") int maxReportsPerTarget,
                     @Param("batchSize") int batchSize);
}
