package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.dto.response.StatsSummaryResponse;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminService {

    ReportResponse submitReport(Long reporterId, SubmitReportRequest request);

    Page<ReportResponse> listReports(ReportStatus status, ReportTargetType targetType, Pageable pageable);

    ReportResponse getReport(Long reportId);

    ReportResponse resolveReport(Long adminId, Long reportId, ResolveReportRequest request);

    Page<ModerationActionResponse> listActions(ReportTargetType targetType, String targetId, Pageable pageable);

    StatsSummaryResponse getStatsSummary();
}
