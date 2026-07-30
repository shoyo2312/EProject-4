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
import com.tiktok.adminservice.exception.ReportAlreadyResolvedException;
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
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adminMapper.toResponse(any(Report.class))).thenReturn(
                new ReportResponse(1L, 10L, ReportTargetType.VIDEO, "v1", "spam content", ReportStatus.PENDING, null, null, null));

        ReportResponse response = adminService.submitReport(10L, request);

        assertThat(response.status()).isEqualTo(ReportStatus.PENDING);
        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
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
                .targetType(ReportTargetType.PRODUCT)
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
}
