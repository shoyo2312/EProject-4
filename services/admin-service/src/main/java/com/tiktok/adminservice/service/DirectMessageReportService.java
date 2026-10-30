package com.tiktok.adminservice.service;

import com.tiktok.adminservice.client.ChatEvidenceClient;
import com.tiktok.adminservice.dto.request.SubmitDirectMessageReportRequest;
import com.tiktok.adminservice.dto.response.ReportEvidenceResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportEvidence;
import com.tiktok.adminservice.entity.ReportSource;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.event.producer.AdminEventProducer;
import com.tiktok.adminservice.exception.InvalidReportReasonException;
import com.tiktok.adminservice.exception.ReportAlreadySubmittedException;
import com.tiktok.adminservice.exception.ReportNotFoundException;
import com.tiktok.adminservice.mapper.AdminMapper;
import com.tiktok.adminservice.repository.ModerationActionRepository;
import com.tiktok.adminservice.repository.ReportEvidenceRepository;
import com.tiktok.adminservice.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A report filed from a conversation: a USER report on the other participant, with the picked messages
 * attached. Kept apart from {@link AdminServiceImpl#submitReport} because it differs on the one point
 * that matters here: the reporter may file again about different messages, and answering with the
 * first report would drop the new evidence.
 */
@Service
@RequiredArgsConstructor
public class DirectMessageReportService {

    private final ReportRepository reportRepository;
    private final ReportEvidenceRepository evidenceRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final ChatEvidenceClient chatEvidenceClient;
    private final AdminMapper adminMapper;
    private final AdminEventProducer adminEventProducer;

    @Transactional
    public ReportResponse submit(Long reporterId, SubmitDirectMessageReportRequest request) {
        if (!DirectMessageReports.REASONS.contains(request.reason())) {
            throw new InvalidReportReasonException(request.reason());
        }
        // A retry of the same submit converges on the report it already made: no second report,
        // second event or second set of evidence.
        Optional<Report> earlier = reportRepository.findByReporterIdAndRequestIdAndDeletedAtIsNull(
                reporterId, request.requestId());
        if (earlier.isPresent()) {
            return adminMapper.toResponse(earlier.get());
        }

        // Before anything is written: if chat-service cannot vouch for the messages there is no report.
        ChatEvidenceClient.Captured captured = chatEvidenceClient.capture(
                reporterId, request.conversationId(), request.messageIds());
        String targetId = ReportTargetType.USER.canonicalTargetId(String.valueOf(captured.reportedUserId()));

        boolean alreadyEnforced = AdminServiceImpl
                .standingEnforcement(moderationActionRepository, ReportTargetType.USER, targetId).isPresent();
        Report report = Report.builder()
                .reporterId(reporterId)
                .targetType(ReportTargetType.USER)
                .targetId(targetId)
                .reason(request.reason())
                .source(ReportSource.DIRECT_MESSAGE)
                .conversationId(request.conversationId())
                .requestId(request.requestId())
                .evidenceCount(captured.messages().size())
                .status(alreadyEnforced ? ReportStatus.RESOLVED : ReportStatus.PENDING)
                .resolvedAt(alreadyEnforced ? Instant.now() : null)
                .build();
        Report saved;
        try {
            saved = reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with the same request: its row cannot be read from this aborted transaction.
            if (e.getCause() instanceof ConstraintViolationException cause
                    && "idx_reports_reporter_request".equalsIgnoreCase(cause.getConstraintName())) {
                throw new ReportAlreadySubmittedException();
            }
            throw e;
        }

        Instant capturedAt = captured.capturedAt() == null ? Instant.now() : captured.capturedAt();
        evidenceRepository.saveAll(captured.messages().stream()
                .map(m -> ReportEvidence.builder()
                        .reportId(saved.getId())
                        .conversationId(request.conversationId())
                        .messageId(m.messageId())
                        .senderId(m.senderId())
                        .messageType(m.type())
                        .sentAt(m.sentAt())
                        .capturedAt(capturedAt)
                        .content(m.text())
                        .stickerId(m.stickerId())
                        .videoId(m.videoId())
                        .storyId(m.storyId())
                        .build())
                .toList());

        if (!alreadyEnforced) {
            adminEventProducer.publishReportSubmitted(saved);
        }
        return adminMapper.toResponse(saved);
    }

    /** The evidence of this one report, and no other: the same account reported by two people has two sets. */
    @Transactional(readOnly = true)
    public ReportEvidenceResponse evidence(Long reportId) {
        Report report = reportRepository.findByIdAndDeletedAtIsNull(reportId)
                .orElseThrow(() -> new ReportNotFoundException(reportId));
        List<ReportEvidence> rows = report.getSource() == ReportSource.DIRECT_MESSAGE
                ? evidenceRepository.findByReportIdAndDeletedAtIsNullOrderBySentAtAscMessageIdAsc(reportId)
                : List.of();
        Long reported = Long.valueOf(report.getTargetId());
        return new ReportEvidenceResponse(report.getId(), report.getConversationId(), report.getReporterId(),
                reported, rows.isEmpty() ? null : rows.get(0).getCapturedAt(),
                rows.stream().map(r -> new ReportEvidenceResponse.Item(r.getMessageId(), r.getSenderId(),
                        r.getSenderId().equals(report.getReporterId()) ? "REPORTER" : "REPORTED",
                        r.getMessageType(), r.getSentAt(), r.getContent(), r.getStickerId(), r.getVideoId(), r.getStoryId())).toList());
    }
}
