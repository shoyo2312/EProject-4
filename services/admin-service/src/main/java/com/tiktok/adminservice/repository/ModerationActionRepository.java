package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    Page<ModerationAction> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType targetType, String targetId, Pageable pageable);

    Page<ModerationAction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtAfter(Instant since);
}
