package com.tiktok.chatservice.realtime;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Tells every open admin console that something it lists may have changed. No payload is parsed or
 * forwarded: the console re-reads through REST, and a report's text has no business on a socket.
 *
 * <p>Its own consumer group, unlike the other fanouts: VideoStateFanout listens to three of these
 * topics, and two listeners in one group split a topic's partitions between them.
 */
@Component
@RequiredArgsConstructor
public class AdminFanout {

    static final String DESTINATION = "/topic/admin";

    private final SimpMessagingTemplate messaging;

    @KafkaListener(groupId = "admin-realtime-${random.uuid}", topics = {
            "admin.report-events",
            "admin.moderation-events",
            "media.video-moderation-events",
            "video.video-events"})
    public void onEvent(ConsumerRecord<String, String> record) {
        Header eventType = record.headers().lastHeader("eventType");
        String type = eventType == null
                ? record.topic()
                : new String(eventType.value(), StandardCharsets.UTF_8);
        messaging.convertAndSend(DESTINATION, new AdminFrame(type));
    }
}
