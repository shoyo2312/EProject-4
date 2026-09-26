package com.tiktok.adminservice.event.producer;

import com.tiktok.adminservice.entity.OutboxEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherTest {

    @Test
    void reportEventsGoToTheirOwnTopic() {
        OutboxEvent row = OutboxEvent.builder().aggregateType(AdminEventProducer.REPORT_AGGREGATE).build();

        assertThat(OutboxPublisher.topicFor(row)).isEqualTo("admin.report-events");
    }

    /** Six services consume admin.moderation-events; a report must not wake every one of them. */
    @Test
    void moderationActionsStayOnTheModerationTopic() {
        OutboxEvent row = OutboxEvent.builder().aggregateType("MODERATION_ACTION").build();

        assertThat(OutboxPublisher.topicFor(row)).isEqualTo("admin.moderation-events");
    }
}
