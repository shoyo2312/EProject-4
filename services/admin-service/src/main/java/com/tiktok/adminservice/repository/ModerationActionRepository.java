package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    Page<ModerationAction> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType targetType, String targetId, Pageable pageable);

    Page<ModerationAction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtAfter(Instant since);

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
