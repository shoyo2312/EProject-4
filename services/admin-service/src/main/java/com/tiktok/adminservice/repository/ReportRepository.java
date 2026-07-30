package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByIdAndDeletedAtIsNull(Long id);

    Page<Report> findByStatusAndDeletedAtIsNull(ReportStatus status, Pageable pageable);

    Page<Report> findByTargetTypeAndDeletedAtIsNull(ReportTargetType targetType, Pageable pageable);

    Page<Report> findByStatusAndTargetTypeAndDeletedAtIsNull(ReportStatus status, ReportTargetType targetType, Pageable pageable);

    Page<Report> findByDeletedAtIsNull(Pageable pageable);

    long countByStatusAndDeletedAtIsNull(ReportStatus status);
}
