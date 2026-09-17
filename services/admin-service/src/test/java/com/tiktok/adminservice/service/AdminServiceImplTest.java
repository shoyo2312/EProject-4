package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.ReportResponse;
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
import com.tiktok.adminservice.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock
    private ReportRepository reportRepository;

    @Mock
    private ModerationActionRepository moderationActionRepository;

    @Mock
    private AdminMapper adminMapper;

    @Mock
    private AdminEventProducer adminEventProducer;

    private AdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        adminService = new AdminServiceImpl(reportRepository, moderationActionRepository, adminMapper, adminEventProducer);
    }

    @Test
    void submitReport_savesPendingReport() {
        SubmitReportRequest request = new SubmitReportRequest(ReportTargetType.VIDEO, "v1", "spam content");
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
                10L, ReportTargetType.VIDEO, "v1")).thenReturn(Optional.empty());
        when(reportRepository.saveAndFlush(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adminMapper.toResponse(any(Report.class))).thenReturn(
                new ReportResponse(1L, 10L, ReportTargetType.VIDEO, "v1", "spam content", ReportStatus.PENDING, null, null, null));

        ReportResponse response = adminService.submitReport(10L, request);

        assertThat(response.status()).isEqualTo(ReportStatus.PENDING);
        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getReporterId()).isEqualTo(10L);
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.PENDING);
    }

    @Test
    void resolveReport_unknownReport_throwsNotFound() {
        when(reportRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminService.resolveReport(1L, 99L, new ResolveReportRequest(ModerationActionType.WARN_USER, "warn")))
                .isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void resolveReport_alreadyResolved_throwsConflict() {
        Report resolved = Report.builder()
                .id(5L)
                .reporterId(1L)
                .targetType(ReportTargetType.VIDEO)
                .targetId("v1")
                .reason("r")
                .status(ReportStatus.RESOLVED)
                .build();
        when(reportRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(resolved));

        assertThatThrownBy(() -> adminService.resolveReport(1L, 5L, new ResolveReportRequest(ModerationActionType.WARN_USER, "warn")))
                .isInstanceOf(ReportAlreadyResolvedException.class);
    }

    @Test
    void resolveReport_takedownVideo_createsActionAndPublishesEvent() {
        Report pending = Report.builder()
                .id(7L)
                .reporterId(1L)
                .targetType(ReportTargetType.VIDEO)
                .targetId("v1")
                .reason("nudity")
                .status(ReportStatus.PENDING)
                .build();
        when(reportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(pending));
        when(adminMapper.toResponse(any(Report.class))).thenReturn(
                new ReportResponse(7L, 1L, ReportTargetType.VIDEO, "v1", "nudity", ReportStatus.RESOLVED, 2L, null, null));

        ReportResponse response = adminService.resolveReport(2L, 7L, new ResolveReportRequest(ModerationActionType.TAKEDOWN_VIDEO, "policy violation"));

        assertThat(response.status()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(pending.getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(pending.getResolvedBy()).isEqualTo(2L);

        ArgumentCaptor<ModerationAction> captor = ArgumentCaptor.forClass(ModerationAction.class);
        verify(moderationActionRepository).save(captor.capture());
        assertThat(captor.getValue().getActionType()).isEqualTo(ModerationActionType.TAKEDOWN_VIDEO);
        assertThat(captor.getValue().getTargetId()).isEqualTo("v1");
        verify(adminEventProducer).publishFor(captor.getValue());
    }

    @Test
    void resolveReport_dismiss_marksReportDismissed() {
        Report pending = Report.builder()
                .id(8L)
                .reporterId(1L)
                .targetType(ReportTargetType.VIDEO)
                .targetId("p1")
                .reason("false report")
                .status(ReportStatus.PENDING)
                .build();
        when(reportRepository.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(pending));
        when(adminMapper.toResponse(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            return new ReportResponse(r.getId(), r.getReporterId(), r.getTargetType(), r.getTargetId(), r.getReason(), r.getStatus(), r.getResolvedBy(), r.getResolvedAt(), null);
        });

        ReportResponse response = adminService.resolveReport(2L, 8L, new ResolveReportRequest(ModerationActionType.DISMISS_REPORT, "not a violation"));

        assertThat(response.status()).isEqualTo(ReportStatus.DISMISSED);
    }

    @Test
    void submitReport_commentTargetIdWithoutVideoId_rejected() {
        SubmitReportRequest request = new SubmitReportRequest(ReportTargetType.COMMENT, "42", "abuse");

        // Accepting this would park an unresolvable report in the queue: it only fails days
        // later, inside the transaction of the admin who tries to act on it.
        assertThatThrownBy(() -> adminService.submitReport(10L, request))
                .isInstanceOf(InvalidModerationTargetException.class);
        verifyNoInteractions(reportRepository);
    }

    @Test
    void submitReport_alreadyReportedByThisUser_returnsTheStandingReport() {
        SubmitReportRequest request = new SubmitReportRequest(ReportTargetType.VIDEO, "v1", "spam content");
        Report existing = Report.builder()
                .id(3L)
                .reporterId(10L)
                .targetType(ReportTargetType.VIDEO)
                .targetId("v1")
                .reason("spam content")
                .status(ReportStatus.PENDING)
                .build();
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
                10L, ReportTargetType.VIDEO, "v1")).thenReturn(Optional.of(existing));
        when(adminMapper.toResponse(existing)).thenReturn(
                new ReportResponse(3L, 10L, ReportTargetType.VIDEO, "v1", "spam content", ReportStatus.PENDING, null, null, null));

        assertThat(adminService.submitReport(10L, request).id()).isEqualTo(3L);
        // Never attempted: the insert would abort the transaction, and the SELECT that would
        // recover from it cannot run afterwards.
        verify(reportRepository, never()).saveAndFlush(any(Report.class));
    }

    @Test
    void submitReport_losesRaceWithConcurrentSubmit_conflicts() {
        SubmitReportRequest request = new SubmitReportRequest(ReportTargetType.VIDEO, "v1", "spam content");
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
                10L, ReportTargetType.VIDEO, "v1")).thenReturn(Optional.empty());
        when(reportRepository.saveAndFlush(any(Report.class)))
                .thenThrow(new DataIntegrityViolationException("idx_reports_one_per_reporter_target"));

        assertThatThrownBy(() -> adminService.submitReport(10L, request))
                .isInstanceOf(ReportAlreadySubmittedException.class);
    }

    @Test
    void resolveReport_banUserOnVideoReport_rejected() {
        Report pending = Report.builder()
                .id(9L)
                .reporterId(1L)
                .targetType(ReportTargetType.VIDEO)
                .targetId("68c1fa3b9e4d2c0001a2b3c4")
                .reason("nudity")
                .status(ReportStatus.PENDING)
                .build();
        when(reportRepository.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(pending));

        // Otherwise this publishes a UserBannedEvent carrying a Mongo document id where a user id
        // belongs — or, when the id happens to parse, a ban aimed at whoever owns that number.
        assertThatThrownBy(() -> adminService.resolveReport(2L, 9L,
                new ResolveReportRequest(ModerationActionType.BAN_USER, "ban")))
                .isInstanceOf(InvalidModerationTargetException.class);
        verifyNoInteractions(moderationActionRepository, adminEventProducer);
        assertThat(pending.getStatus()).isEqualTo(ReportStatus.PENDING);
    }
}
