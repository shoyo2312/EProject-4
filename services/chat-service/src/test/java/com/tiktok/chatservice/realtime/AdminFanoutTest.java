package com.tiktok.chatservice.realtime;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AdminFanoutTest {

    @Mock
    private SimpMessagingTemplate messaging;

    @Test
    void relaysTheEventTypeAndNothingElse() {
        AdminFanout fanout = new AdminFanout(messaging);
        fanout.onEvent(record("admin.report-events", "ReportSubmittedEvent", "{\"reportId\":7,\"targetId\":\"v1\"}"));

        fanout.flush();

        verify(messaging).convertAndSend(AdminFanout.DESTINATION, new AdminFrame("ReportSubmittedEvent"));
    }

    /** media-worker's verdicts carry no eventType header. */
    @Test
    void fallsBackToTheTopicWhenTheProducerSetNoType() {
        AdminFanout fanout = new AdminFanout(messaging);
        fanout.onEvent(record("media.video-moderation-events", null, "{}"));

        fanout.flush();

        verify(messaging).convertAndSend(AdminFanout.DESTINATION, new AdminFrame("media.video-moderation-events"));
    }

    /**
     * An account going private moves every video it owns, one VideoVisibilityChangedEvent each.
     * Every frame is a REST re-read by every open console, so the burst has to stop here.
     */
    @Test
    void aBurstOfOneTypeIsOneFramePerWindow() {
        AdminFanout fanout = new AdminFanout(messaging);
        for (int i = 0; i < 200; i++) {
            fanout.onEvent(record("video.video-events", "VideoVisibilityChangedEvent", "{}"));
        }
        fanout.onEvent(record("admin.report-events", "ReportSubmittedEvent", "{}"));

        fanout.flush();

        verify(messaging, times(1)).convertAndSend(AdminFanout.DESTINATION, new AdminFrame("VideoVisibilityChangedEvent"));
        verify(messaging, times(1)).convertAndSend(AdminFanout.DESTINATION, new AdminFrame("ReportSubmittedEvent"));
    }

    @Test
    void aQuietWindowSendsNothing() {
        AdminFanout fanout = new AdminFanout(messaging);
        fanout.flush();
        verifyNoInteractions(messaging);

        fanout.onEvent(record("admin.report-events", "ReportResolvedEvent", "{}"));
        fanout.flush();
        fanout.flush();

        // The second window had nothing new: what the first one sent is not sent again.
        verify(messaging, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void flushesOnTheCounterFlushersWindow() throws Exception {
        Scheduled scheduled = AdminFanout.class.getMethod("flush").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${realtime.flush-interval-millis}");
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

    private static ConsumerRecord<String, String> record(String topic, String eventType, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, "v1", payload);
        if (eventType != null) {
            record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
