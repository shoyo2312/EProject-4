package com.tiktok.adminservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.adminservice.entity.ModerationAction;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.OutboxEvent;
import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.repository.OutboxEventRepository;
import com.tiktok.event.admin.ReportResolvedEvent;
import com.tiktok.event.admin.ReportSubmittedEvent;
import com.tiktok.event.admin.StaleReportsDismissedEvent;
import com.tiktok.event.admin.UserSessionsRevokedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminEventProducerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AdminEventProducer producer() {
        return new AdminEventProducer(outboxEventRepository, objectMapper);
    }

    private OutboxEvent saved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void reportSubmitted_isWrittenUnderTheReportAggregate_withoutTheReportersWords() throws Exception {
        Report report = Report.builder()
                .id(7L)
                .reporterId(99L)
                .targetType(ReportTargetType.VIDEO)
                .targetId("v1")
                .reason("my neighbour posted this")
                .status(ReportStatus.PENDING)
                .build();

        producer().publishReportSubmitted(report);

        OutboxEvent row = saved();
        assertThat(row.getAggregateType()).isEqualTo(AdminEventProducer.REPORT_AGGREGATE);
        assertThat(row.getAggregateId()).isEqualTo("v1");
        assertThat(row.getEventType()).isEqualTo("ReportSubmittedEvent");
        ReportSubmittedEvent event = objectMapper.readValue(row.getPayload(), ReportSubmittedEvent.class);
        assertThat(event.reportId()).isEqualTo(7L);
        assertThat(event.targetType()).isEqualTo("VIDEO");
        assertThat(event.targetId()).isEqualTo("v1");
        // Every admin console receives this; the reporter's text and identity stay behind REST.
        assertThat(row.getPayload()).doesNotContain("neighbour");
        assertThat(objectMapper.readTree(row.getPayload()).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventId", "occurredAt", "reportId", "targetType", "targetId");
    }

    @Test
    void reportsResolved_isWrittenUnderTheReportAggregate() throws Exception {
        producer().publishReportsResolved(ReportTargetType.USER, "42");

        OutboxEvent row = saved();
        assertThat(row.getAggregateType()).isEqualTo(AdminEventProducer.REPORT_AGGREGATE);
        assertThat(row.getEventType()).isEqualTo("ReportResolvedEvent");
        ReportResolvedEvent event = objectMapper.readValue(row.getPayload(), ReportResolvedEvent.class);
        assertThat(event.targetType()).isEqualTo("USER");
        assertThat(event.targetId()).isEqualTo("42");
    }

    @Test
    void staleReportsDismissed_isWrittenUnderTheReportAggregate() throws Exception {
        producer().publishStaleReportsDismissed(37);

        OutboxEvent row = saved();
        // admin.report-events, where the consoles listen; not admin.moderation-events, whose
        // consumers act on what they read.
        assertThat(row.getAggregateType()).isEqualTo(AdminEventProducer.REPORT_AGGREGATE);
        assertThat(row.getAggregateId()).isEqualTo(AdminEventProducer.STALE_SWEEP_KEY);
        assertThat(row.getEventType()).isEqualTo("StaleReportsDismissedEvent");
        assertThat(objectMapper.readValue(row.getPayload(), StaleReportsDismissedEvent.class).dismissed())
                .isEqualTo(37);
    }

    /**
     * Keyed by the account like a ban, so a sign-out and a ban of the same account keep their
     * order on the partition — and carrying the user id auth-service acts on.
     */
    @Test
    void revokingSessions_isAnEventAuthServiceCanActOn() throws Exception {
        producer().publishFor(ModerationAction.builder()
                .adminId(2L)
                .actionType(ModerationActionType.REVOKE_SESSIONS)
                .targetType(ReportTargetType.USER)
                .targetId("42")
                .reason("account taken over")
                .build());

        OutboxEvent row = saved();
        assertThat(row.getAggregateId()).isEqualTo("42");
        assertThat(row.getEventType()).isEqualTo("UserSessionsRevokedEvent");
        UserSessionsRevokedEvent event = objectMapper.readValue(row.getPayload(), UserSessionsRevokedEvent.class);
        assertThat(event.userId()).isEqualTo(42L);
        assertThat(event.adminId()).isEqualTo(2L);
    }

    @Test
    void moderationActions_keepTheirOwnAggregate() {
        producer().publishFor(ModerationAction.builder()
                .adminId(2L)
                .actionType(ModerationActionType.TAKEDOWN_VIDEO)
                .targetType(ReportTargetType.VIDEO)
                .targetId("v1")
                .reason("policy violation")
                .build());

        assertThat(saved().getAggregateType()).isEqualTo("MODERATION_ACTION");
    }
}
