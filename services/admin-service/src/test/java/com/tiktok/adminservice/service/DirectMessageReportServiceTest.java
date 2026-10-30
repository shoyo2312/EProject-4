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
import com.tiktok.adminservice.exception.ReportEvidenceRejectedException;
import com.tiktok.adminservice.mapper.AdminMapper;
import com.tiktok.adminservice.repository.ModerationActionRepository;
import com.tiktok.adminservice.repository.ReportEvidenceRepository;
import com.tiktok.adminservice.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirectMessageReportServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportEvidenceRepository evidenceRepository;
    @Mock
    private ModerationActionRepository moderationActionRepository;
    @Mock
    private ChatEvidenceClient chatEvidenceClient;
    @Mock
    private AdminMapper adminMapper;
    @Mock
    private AdminEventProducer adminEventProducer;

    private DirectMessageReportService service;

    @BeforeEach
    void setUp() {
        service = new DirectMessageReportService(reportRepository, evidenceRepository, moderationActionRepository,
                chatEvidenceClient, adminMapper, adminEventProducer);
    }

    private static SubmitDirectMessageReportRequest request(String reason) {
        return new SubmitDirectMessageReportRequest("c1", reason, List.of("m1", "m2"), "req-1");
    }

    private ChatEvidenceClient.Captured captured() {
        return new ChatEvidenceClient.Captured("c1", 2L, T0.plusSeconds(100), List.of(
                new ChatEvidenceClient.Message("m1", 2L, "TEXT", T0, "hello", null, null),
                new ChatEvidenceClient.Message("m2", 1L, "TEXT", T0.plusSeconds(5), "context", null, null)));
    }

    @Test
    void filesOneUserReportWithEvidenceAndPublishesOnce() {
        when(reportRepository.findByReporterIdAndRequestIdAndDeletedAtIsNull(1L, "req-1")).thenReturn(Optional.empty());
        when(chatEvidenceClient.capture(1L, "c1", List.of("m1", "m2"))).thenReturn(captured());
        when(reportRepository.saveAndFlush(any(Report.class))).thenAnswer(i -> i.getArgument(0));

        service.submit(1L, request("Hate and harassment"));

        ArgumentCaptor<Report> report = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(report.capture());
        assertThat(report.getValue().getTargetType()).isEqualTo(ReportTargetType.USER);
        assertThat(report.getValue().getTargetId()).isEqualTo("2");
        assertThat(report.getValue().getReporterId()).isEqualTo(1L);
        assertThat(report.getValue().getSource()).isEqualTo(ReportSource.DIRECT_MESSAGE);
        assertThat(report.getValue().getEvidenceCount()).isEqualTo(2);
        assertThat(report.getValue().getStatus()).isEqualTo(ReportStatus.PENDING);
        verify(evidenceRepository).saveAll(any(List.class));
        verify(adminEventProducer).publishReportSubmitted(report.getValue());
    }

    @Test
    void retryWithTheSameRequestIdReturnsTheStoredReportAndWritesNothing() {
        Report stored = Report.builder().id(9L).reporterId(1L).targetType(ReportTargetType.USER).targetId("2")
                .reason("Other").status(ReportStatus.PENDING).build();
        ReportResponse response = new ReportResponse(9L, 1L, ReportTargetType.USER, "2", "Other",
                ReportStatus.PENDING, null, null, null, ReportSource.DIRECT_MESSAGE, "c1", 2);
        when(reportRepository.findByReporterIdAndRequestIdAndDeletedAtIsNull(1L, "req-1")).thenReturn(Optional.of(stored));
        when(adminMapper.toResponse(stored)).thenReturn(response);

        assertThat(service.submit(1L, request("Other"))).isSameAs(response);

        verifyNoInteractions(chatEvidenceClient, evidenceRepository, adminEventProducer);
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void unknownReasonAndRejectedEvidenceFileNothing() {
        assertThatThrownBy(() -> service.submit(1L, request("made up")))
                .isInstanceOf(InvalidReportReasonException.class);

        when(reportRepository.findByReporterIdAndRequestIdAndDeletedAtIsNull(1L, "req-1")).thenReturn(Optional.empty());
        when(chatEvidenceClient.capture(eq(1L), eq("c1"), any()))
                .thenThrow(new ReportEvidenceRejectedException("REPORT_EVIDENCE_UNAVAILABLE", "gone", HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.submit(1L, request("Other")))
                .isInstanceOf(ReportEvidenceRejectedException.class);

        verify(reportRepository, never()).saveAndFlush(any());
        verifyNoInteractions(evidenceRepository, adminEventProducer);
    }

    @Test
    void evidenceLabelsEachSenderAndIsScopedToTheReport() {
        Report report = Report.builder().id(9L).reporterId(1L).targetType(ReportTargetType.USER).targetId("2")
                .reason("Other").source(ReportSource.DIRECT_MESSAGE).conversationId("c1").status(ReportStatus.PENDING).build();
        when(reportRepository.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(report));
        when(evidenceRepository.findByReportIdAndDeletedAtIsNullOrderBySentAtAscMessageIdAsc(9L)).thenReturn(List.of(
                ReportEvidence.builder().reportId(9L).messageId("m1").senderId(2L).messageType("TEXT").sentAt(T0)
                        .capturedAt(T0).conversationId("c1").content("hello").build(),
                ReportEvidence.builder().reportId(9L).messageId("m2").senderId(1L).messageType("TEXT").sentAt(T0)
                        .capturedAt(T0).conversationId("c1").content("context").build()));

        ReportEvidenceResponse response = service.evidence(9L);

        assertThat(response.reportedUserId()).isEqualTo(2L);
        assertThat(response.messages()).extracting(ReportEvidenceResponse.Item::role).containsExactly("REPORTED", "REPORTER");
    }

    @Test
    void profileReportHasNoEvidence() {
        Report report = Report.builder().id(5L).reporterId(1L).targetType(ReportTargetType.USER).targetId("2")
                .reason("Other").status(ReportStatus.PENDING).build();
        when(reportRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(report));

        assertThat(service.evidence(5L).messages()).isEmpty();
        verifyNoInteractions(evidenceRepository);
    }
}
