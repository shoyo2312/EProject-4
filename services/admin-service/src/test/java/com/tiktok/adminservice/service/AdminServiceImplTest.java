package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.ReportGroupResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.repository.ReportQueueRow;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
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

    /**
     * What counts as a strike. WARN_USER and DISMISS_REPORT are decisions about a report, not
     * about the account, and a count that included them would call an admin who dismissed four
     * reports in someone's favour a four-strike offender.
     */
    @Test
    void countStrikes_countsEnforcementOnly() {
        assertThat(ModerationActionType.ENFORCING)
                .containsExactlyInAnyOrder(ModerationActionType.BAN_USER,
                        ModerationActionType.TAKEDOWN_VIDEO, ModerationActionType.REMOVE_COMMENT);

        when(moderationActionRepository.countByTargetTypeAndTargetIdAndActionTypeIn(
                ReportTargetType.USER, "7", ModerationActionType.ENFORCING)).thenReturn(3L);

        assertThat(adminService.countStrikes(ReportTargetType.USER, "7")).isEqualTo(3L);
    }

    /** A banned account's strikes must survive the unban — see countStrikes' contract. */
    @Test
    void countStrikes_doesNotSubtractReversals() {
        assertThat(ModerationActionType.ENFORCING)
                .doesNotContain(ModerationActionType.UNBAN_USER, ModerationActionType.RESTORE_VIDEO);
    }

    @Test
    void moderate_turnsBanDaysIntoADeadlineOnTheAuditRow() {
        when(moderationActionRepository.save(any(ModerationAction.class))).thenAnswer(inv -> inv.getArgument(0));

        adminService.moderate(1L, ReportTargetType.USER, "7", ModerationActionType.BAN_USER, "spam", 7);

        ArgumentCaptor<ModerationAction> saved = ArgumentCaptor.forClass(ModerationAction.class);
        verify(moderationActionRepository).save(saved.capture());
        assertThat(saved.getValue().getBannedUntil())
                .isCloseTo(Instant.now().plus(7, ChronoUnit.DAYS), within(1, ChronoUnit.MINUTES));
    }

    /** No duration is a ban that does not lapse, which is what every ban was before this. */
    @Test
    void moderate_leavesTheDeadlineUnsetWhenNoDurationIsGiven() {
        when(moderationActionRepository.save(any(ModerationAction.class))).thenAnswer(inv -> inv.getArgument(0));

        adminService.moderate(1L, ReportTargetType.USER, "7", ModerationActionType.BAN_USER, "spam");

        ArgumentCaptor<ModerationAction> saved = ArgumentCaptor.forClass(ModerationAction.class);
        verify(moderationActionRepository).save(saved.capture());
        assertThat(saved.getValue().getBannedUntil()).isNull();
    }

    /** A takedown has no duration, and nothing reverses one on a timer. */
    @Test
    void moderate_ignoresADurationOnAnythingButABan() {
        when(moderationActionRepository.save(any(ModerationAction.class))).thenAnswer(inv -> inv.getArgument(0));

        adminService.moderate(1L, ReportTargetType.VIDEO, "v1", ModerationActionType.TAKEDOWN_VIDEO, "nudity", 7);

        ArgumentCaptor<ModerationAction> saved = ArgumentCaptor.forClass(ModerationAction.class);
        verify(moderationActionRepository).save(saved.capture());
        assertThat(saved.getValue().getBannedUntil()).isNull();
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

    @Test
    void listActions_targetTypeWithoutTargetId_rejected() {
        assertThatThrownBy(() -> adminService.listActions(ReportTargetType.USER, null, Pageable.unpaged()))
                .isInstanceOf(InvalidModerationTargetException.class);
        verifyNoInteractions(moderationActionRepository);
    }

    @Test
    void submitReport_targetAlreadyTakenDown_closesTheReportOnArrival() {
        SubmitReportRequest request = new SubmitReportRequest(ReportTargetType.VIDEO, "v1", "Nudity and sexual content");
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
                10L, ReportTargetType.VIDEO, "v1")).thenReturn(Optional.empty());
        standingAction(ModerationActionType.TAKEDOWN_VIDEO);
        when(reportRepository.saveAndFlush(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adminMapper.toResponse(any(Report.class))).thenReturn(
                new ReportResponse(1L, 10L, ReportTargetType.VIDEO, "v1", "Nudity and sexual content", ReportStatus.RESOLVED, null, null, null));

        adminService.submitReport(10L, request);

        // The report is kept — someone objected, and that is the record — but an admin never sees
        // it: the video is already gone, and the queue would fill with rows deciding nothing.
        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.RESOLVED);
        assertThat(captor.getValue().getResolvedAt()).isNotNull();
        // Null resolvedBy is what distinguishes this from a decision an admin made.
        assertThat(captor.getValue().getResolvedBy()).isNull();
    }

    @Test
    void submitReport_targetRestoredAfterTakedown_staysPending() {
        SubmitReportRequest request = new SubmitReportRequest(ReportTargetType.VIDEO, "v1", "Hate and harassment");
        when(reportRepository.findByReporterIdAndTargetTypeAndTargetIdAndDeletedAtIsNull(
                10L, ReportTargetType.VIDEO, "v1")).thenReturn(Optional.empty());
        // The newest state-changing action undid the takedown, so the video is up and this report
        // is about what is on the platform right now.
        standingAction(ModerationActionType.RESTORE_VIDEO);
        when(reportRepository.saveAndFlush(any(Report.class))).thenAnswer(inv -> inv.getArgument(0));
        when(adminMapper.toResponse(any(Report.class))).thenReturn(
                new ReportResponse(1L, 10L, ReportTargetType.VIDEO, "v1", "Hate and harassment", ReportStatus.PENDING, null, null, null));

        adminService.submitReport(10L, request);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ReportStatus.PENDING);
    }

    @Test
    void resolveReport_closesEveryOtherReportAgainstTheSameTarget() {
        Report pending = Report.builder()
                .id(7L)
                .reporterId(1L)
                .targetType(ReportTargetType.VIDEO)
                .targetId("v1")
                .reason("Nudity and sexual content")
                .status(ReportStatus.PENDING)
                .build();
        when(reportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(pending));
        when(adminMapper.toResponse(any(Report.class))).thenReturn(
                new ReportResponse(7L, 1L, ReportTargetType.VIDEO, "v1", "x", ReportStatus.RESOLVED, 2L, null, null));

        adminService.resolveReport(2L, 7L, new ResolveReportRequest(ModerationActionType.TAKEDOWN_VIDEO, "policy violation"));

        // Everyone else who flagged the same video is answered by the same decision; leaving them
        // PENDING is how the video comes back up the queue with an admin about to decide it twice.
        // The report resolved through JPA is excluded, or the bulk update overwrites it.
        verify(reportRepository).closePendingFor(eq(ReportTargetType.VIDEO), eq("v1"),
                eq(ReportStatus.RESOLVED), eq(2L), eq(7L), any(Instant.class));
    }

    @Test
    void moderate_closesStandingReportsAgainstThatTarget() {
        adminService.moderate(2L, ReportTargetType.VIDEO, "v1", ModerationActionType.TAKEDOWN_VIDEO, "policy violation");

        // A takedown taken straight from the video listing, with no report behind it, still
        // answers the reports that were filed about it.
        verify(reportRepository).closePendingFor(eq(ReportTargetType.VIDEO), eq("v1"),
                eq(ReportStatus.RESOLVED), eq(2L), isNull(), any(Instant.class));
    }

    @Test
    void moderate_restoringAVideoDismissesTheReportsRatherThanResolvingThem() {
        adminService.moderate(2L, ReportTargetType.VIDEO, "v1", ModerationActionType.RESTORE_VIDEO, "appeal upheld");

        // RESOLVED would record in the audit log that the reports were upheld, which is the
        // opposite of what restoring the video decided.
        verify(reportRepository).closePendingFor(eq(ReportTargetType.VIDEO), eq("v1"),
                eq(ReportStatus.DISMISSED), eq(2L), isNull(), any(Instant.class));
    }

    @Test
    void listReportQueue_dropsAnySortTheCallerAsksFor() {
        when(reportRepository.findPendingQueue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        adminService.listReportQueue(PageRequest.of(1, 20, org.springframework.data.domain.Sort.by("createdAt")));

        // The query carries its own ORDER BY; a sort from the query string is appended to it as a
        // second one that never applies, and ordering the worklist is not the caller's call.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reportRepository).findPendingQueue(captor.capture());
        assertThat(captor.getValue().getSort().isSorted()).isFalse();
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    void listReportQueue_mapsOneRowPerTarget() {
        ReportQueueRow row = mock(ReportQueueRow.class);
        when(row.getTargetType()).thenReturn("VIDEO");
        when(row.getTargetId()).thenReturn("v1");
        when(row.getReportCount()).thenReturn(12L);
        when(row.getSeverity()).thenReturn(10);
        when(row.getPriority()).thenReturn(120L);
        when(reportRepository.findPendingQueue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        ReportGroupResponse group = adminService.listReportQueue(PageRequest.of(0, 20)).getContent().get(0);

        assertThat(group.targetType()).isEqualTo(ReportTargetType.VIDEO);
        assertThat(group.reportCount()).isEqualTo(12L);
        assertThat(group.priority()).isEqualTo(120L);
    }

    /** The newest decision that moved this target in or out of enforcement. */
    private void standingAction(ModerationActionType actionType) {
        when(moderationActionRepository.findFirstByTargetTypeAndTargetIdAndActionTypeInOrderByCreatedAtDesc(
                any(ReportTargetType.class), anyString(), any()))
                .thenReturn(Optional.of(ModerationAction.builder().actionType(actionType).build()));
    }
}
