package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    /**
     * The audit log, narrowed by any combination of the console's filters. A null filter is not
     * applied, so all-null is the whole log. {@code from} is inclusive and {@code to} exclusive, so
     * consecutive days tile without a row landing in both.
     *
     * <p>Ordered by the pageable's sort alone. An ORDER BY written here would come first and the
     * pageable's would be appended after it, where it never decides anything — which is how the
     * console's sort controls once did nothing. {@code AdminServiceImpl} supplies the default.
     *
     * <p>One query rather than a derived method per combination, like the report ledger's. The two
     * instants are cast in their null checks because PgJDBC sends a timestamp parameter untyped,
     * and Postgres cannot type a bare {@code ? IS NULL} on its own — it fails the whole statement.
     */
    @Query("""
            SELECT a FROM ModerationAction a
            WHERE (:targetType IS NULL OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND (:adminId IS NULL OR a.adminId = :adminId)
              AND (:actionType IS NULL OR a.actionType = :actionType)
              AND (CAST(:from AS Instant) IS NULL OR a.createdAt >= :from)
              AND (CAST(:to AS Instant) IS NULL OR a.createdAt < :to)
            """)
    Page<ModerationAction> findFiltered(@Param("targetType") ReportTargetType targetType,
                                        @Param("targetId") String targetId,
                                        @Param("adminId") Long adminId,
                                        @Param("actionType") ModerationActionType actionType,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        Pageable pageable);

    long countByCreatedAtAfter(Instant since);

    /**
     * Actions taken per day since the cutoff, split by decision — the flows every delta in the
     * console compares. Aliases are quoted because Postgres folds unquoted ones to lower case
     * and the projection would then bind nothing.
     *
     * <p>FILTER rather than four separate queries: the same index scan answers all of them, and
     * a day on which nobody banned anyone still has to come back as a zero inside its row rather
     * than as a missing row in one of four lists.
     */
    @Query(value = """
            SELECT date_trunc('day', created_at)::date                         AS "day",
                   COUNT(*)                                                    AS "taken",
                   COUNT(*) FILTER (WHERE action_type = 'BAN_USER')            AS "usersBanned",
                   COUNT(*) FILTER (WHERE action_type = 'TAKEDOWN_VIDEO')      AS "videosTakenDown",
                   COUNT(*) FILTER (WHERE action_type = 'REMOVE_COMMENT')      AS "commentsRemoved"
            FROM moderation_actions
            WHERE created_at >= :since
            GROUP BY "day"
            ORDER BY "day"
            """, nativeQuery = true)
    List<DailyActionRow> findDailyActions(@Param("since") Instant since);

    long countByTargetTypeAndTargetIdAndActionTypeIn(
            ReportTargetType targetType, String targetId, Collection<ModerationActionType> actionTypes);

    /**
     * The last decision that moved this target in or out of enforcement — what the console means
     * by "is this already dealt with". Callers pass
     * {@link ModerationActionType#STATE_CHANGING}; a WARN_USER or DISMISS_REPORT filed afterwards
     * is not an answer to that question, and letting it come back first would report a video
     * that is still down as untouched.
     */
    Optional<ModerationAction> findFirstByTargetTypeAndTargetIdAndActionTypeInOrderByCreatedAtDesc(
            ReportTargetType targetType, String targetId, Collection<ModerationActionType> actionTypes);
}
