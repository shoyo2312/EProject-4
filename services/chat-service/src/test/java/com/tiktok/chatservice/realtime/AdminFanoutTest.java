package com.tiktok.chatservice.realtime;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AdminFanoutTest {

    @Mock
    private SimpMessagingTemplate messaging;

    @Test
    void relaysTheEventTypeAndNothingElse() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "admin.report-events", 0, 0L, "v1", "{\"reportId\":7,\"targetId\":\"v1\"}");
        record.headers().add("eventType", "ReportSubmittedEvent".getBytes(StandardCharsets.UTF_8));

        new AdminFanout(messaging).onEvent(record);

        verify(messaging).convertAndSend(AdminFanout.DESTINATION, new AdminFrame("ReportSubmittedEvent"));
    }

    /** media-worker's verdicts carry no eventType header. */
    @Test
    void fallsBackToTheTopicWhenTheProducerSetNoType() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "media.video-moderation-events", 0, 0L, "v1", "{}");

        new AdminFanout(messaging).onEvent(record);

        verify(messaging).convertAndSend(AdminFanout.DESTINATION, new AdminFrame("media.video-moderation-events"));
    }

    /**
     * VideoStateFanout consumes three of the same topics. In the service-wide group the two
     * listeners would split those topics' partitions, and each would see only part of the stream.
     */
    @Test
    void consumesInItsOwnGroup() throws Exception {
        KafkaListener listener = AdminFanout.class
                .getMethod("onEvent", ConsumerRecord.class)
                .getAnnotation(KafkaListener.class);

        assertThat(listener.groupId()).startsWith("admin-realtime-");
        assertThat(listener.topics()).containsExactlyInAnyOrder(
                "admin.report-events", "admin.moderation-events",
                "media.video-moderation-events", "video.video-events", "auth.user-events");
    }
}
