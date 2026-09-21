package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.ReportGroupResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.dto.response.StatsSummaryResponse;
import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.event.producer.AdminEventProducer;
import com.tiktok.adminservice.exception.InvalidModerationTargetException;
import com.tiktok.adminservice.exception.ReportAlreadyResolvedException;
import com.tiktok.adminservice.exception.ReportAlreadySubmittedException;
import com.tiktok.adminservice.exception.ReportNotFoundException;
import com.tiktok.adminservice.mapper.AdminMapper;
import com.tiktok.adminservice.repository.ModerationActionRepository;
import com.tiktok.adminservice.repository.ReportQueueRow;
import com.tiktok.adminservice.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

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
        // Checked here and not only at resolve time: an id nobody can act on is a report that
        // cannot be closed, discovered days later by the admin who tries.
        request.targetType().validateTargetId(request.targetId());

        // The ordinary repeat — the same user reporting the same thing again — answers with the
        // report they already filed. Reading first rather than inserting and recovering: Postgres
        // aborts the transaction on a constraint violation, so a SELECT in the catch block runs
        // against a dead transaction and fails with "current transaction is aborted".
        Optional<Report> existing = reportRepository
                .findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
                        reporterId, request.targetType(), request.targetId());
        if (existing.isPresent()) {
            return adminMapper.toResponse(existing.get());
        }

        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType(request.targetType())
                .targetId(request.targetId())
                .reason(request.reason())
                .status(ReportStatus.PENDING)
                .build();

        try {
            // Flushed here so the unique index answers inside this method. Left to commit time it
            // would surface as a raw DataIntegrityViolationException from the transaction proxy
            // and be served as a 500.
            return adminMapper.toResponse(reportRepository.saveAndFlush(report));
        } catch (DataIntegrityViolationException e) {
            // Lost the race with a concurrent submit of the same report. 409 rather than the
            // other request's row, because that row cannot be read from this transaction any more.
            throw new ReportAlreadySubmittedException();
        }
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
    public Page<ReportGroupResponse> listReportQueue(Pageable pageable) {
        // Page and size only: the query carries its own ORDER BY, and a sort from the query
        // string would be appended to it as a second one that never gets a chance to apply.
        // Ordering the worklist is the queue's job, not the caller's.
        Page<ReportQueueRow> rows = reportRepository.findPendingQueue(
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        return rows.map(row -> new ReportGroupResponse(
                ReportTargetType.valueOf(row.getTargetType()),
                row.getTargetId(),
                row.getReportCount(),
                row.getFirstReportedAt(),
                row.getLastReportedAt(),
                row.getLatestReason(),
                row.getSeverity(),
                row.getPriority()));
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

        // The request picks an action, the report supplies the target, and nothing else pairs
        // them up — see ModerationActionType. Reports predating the submit-time check above can
        // still carry an id this action cannot use, so both halves are verified.
        request.actionType().requireApplicableTo(report.getTargetType());
        report.getTargetType().validateTargetId(report.getTargetId());

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
    @Transactional
    public ModerationActionResponse moderate(Long adminId, ReportTargetType targetType, String targetId,
                                            ModerationActionType actionType, String reason) {
        actionType.requireApplicableTo(targetType);
        targetType.validateTargetId(targetId);

        // No existence check against the owning service: this one cannot read its database, and an
        // extra HTTP call would only move the failure. An action against an id that does not exist
        // is a no-op on the consumer side, and the audit row is still the honest record of the
        // decision. Consumers are required to ignore unknown ids for exactly this reason.
        ModerationAction action = ModerationAction.builder()
                .adminId(adminId)
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .reason(reason)
                .build();
        moderationActionRepository.save(action);
        adminEventProducer.publishFor(action);

        return adminMapper.toResponse(action);
    }

    @Override
    public Page<ModerationActionResponse> listActions(ReportTargetType targetType, String targetId, Pageable pageable) {
        // Both or neither. Silently ignoring a half-filled filter answers a narrow question with
        // the whole platform's audit log, which reads as "no actions were taken against this one".
        if (targetType == null ^ targetId == null) {
            throw new InvalidModerationTargetException(
                    "targetType and targetId must be given together, or both omitted");
        }

        Page<ModerationAction> actions = targetType != null
                ? moderationActionRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId, pageable)
                : moderationActionRepository.findAllByOrderByCreatedAtDesc(pageable);
        return actions.map(adminMapper::toResponse);
    }

    @Override
    public long countReports(ReportTargetType targetType, String targetId) {
        return reportRepository.countByTargetTypeAndTargetIdAndDeletedAtIsNull(targetType, targetId);
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
