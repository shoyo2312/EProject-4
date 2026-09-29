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
