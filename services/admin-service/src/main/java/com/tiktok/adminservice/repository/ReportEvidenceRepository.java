package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.ReportEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportEvidenceRepository extends JpaRepository<ReportEvidence, Long> {

    List<ReportEvidence> findByReportIdAndDeletedAtIsNullOrderBySentAtAscMessageIdAsc(Long reportId);
}
