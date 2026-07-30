package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.dto.response.StatsSummaryResponse;
import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.event.producer.AdminEventProducer;
import com.tiktok.adminservice.exception.ReportAlreadyResolvedException;
import com.tiktok.adminservice.exception.ReportNotFoundException;
import com.tiktok.adminservice.mapper.AdminMapper;
import com.tiktok.adminservice.repository.ModerationActionRepository;
import com.tiktok.adminservice.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final ReportRepository reportRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final AdminMapper adminMapper;
    private final AdminEventProducer adminEventProducer;

    @Override
    @Transactional
    public ReportResponse submitReport(Long reporterId, SubmitReportRequest request) {
        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType(request.targetType())
                .targetId(request.targetId())
                .reason(request.reason())
                .status(ReportStatus.PENDING)
                .build();

        return adminMapper.toResponse(reportRepository.save(report));
    }

    @Override
    public Page<ReportResponse> listReports(ReportStatus status, ReportTargetType targetType, Pageable pageable) {
        Page<Report> reports;
        if (status != null && targetType != null) {
            reports = reportRepository.findByStatusAndTargetTypeAndDeletedAtIsNull(status, targetType, pageable);
        } else if (status != null) {
            reports = reportRepository.findByStatusAndDeletedAtIsNull(status, pageable);
        } else if (targetType != null) {
            reports = reportRepository.findByTargetTypeAndDeletedAtIsNull(targetType, pageable);
        } else {
            reports = reportRepository.findByDeletedAtIsNull(pageable);
        }
        return reports.map(adminMapper::toResponse);
    }

    @Override
    public ReportResponse getReport(Long reportId) {
        return adminMapper.toResponse(findVisible(reportId));
    }

    @Override
    @Transactional
    public ReportResponse resolveReport(Long adminId, Long reportId, ResolveReportRequest request) {
        Report report = findVisible(reportId);

        if (report.getStatus() != ReportStatus.PENDING) {
            throw new ReportAlreadyResolvedException(reportId);
        }

        ModerationAction action = ModerationAction.builder()
                .adminId(adminId)
                .actionType(request.actionType())
                .targetType(report.getTargetType())
                .targetId(report.getTargetId())
                .reason(request.reason())
                .reportId(report.getId())
                .build();
        moderationActionRepository.save(action);
        adminEventProducer.publishFor(action);

        ReportStatus finalStatus = request.actionType() == ModerationActionType.DISMISS_REPORT
                ? ReportStatus.DISMISSED
                : ReportStatus.RESOLVED;
        report.resolve(adminId, finalStatus);

        return adminMapper.toResponse(report);
    }

    @Override
    public Page<ModerationActionResponse> listActions(ReportTargetType targetType, String targetId, Pageable pageable) {
        Page<ModerationAction> actions = targetType != null && targetId != null
                ? moderationActionRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable)
                : moderationActionRepository.findAllByOrderByCreatedAtDesc(pageable);
        return actions.map(adminMapper::toResponse);
    }

    @Override
    public StatsSummaryResponse getStatsSummary() {
        return new StatsSummaryResponse(
                reportRepository.countByStatusAndDeletedAtIsNull(ReportStatus.PENDING),
                reportRepository.countByStatusAndDeletedAtIsNull(ReportStatus.RESOLVED),
                reportRepository.countByStatusAndDeletedAtIsNull(ReportStatus.DISMISSED),
                moderationActionRepository.countByCreatedAtAfter(Instant.now().minus(24, ChronoUnit.HOURS)));
    }

    private Report findVisible(Long reportId) {
        return reportRepository.findByIdAndDeletedAtIsNull(reportId)
                .orElseThrow(() -> new ReportNotFoundException(reportId));
    }
}
