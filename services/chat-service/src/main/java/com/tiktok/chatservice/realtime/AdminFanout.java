package com.tiktok.chatservice.realtime;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells every open admin console that something it lists may have changed. No payload is parsed or
 * forwarded: the console re-reads through REST, and a report's text has no business on a socket.
 *
 * <p>At most one frame per event type per flush window, the same window the counter flushers use.
 * A record is only a reason to re-read, and some arrive in bursts: an account going private moves
 * every video it owns, each with its own VideoVisibilityChangedEvent, and relayed one for one that
 * was as many REST re-reads from every open console.
 *
 * <p>Its own consumer group, unlike the other fanouts: VideoStateFanout listens to three of these
 * topics, and two listeners in one group split a topic's partitions between them.
 */
@Component
@RequiredArgsConstructor
public class AdminFanout {

    static final String DESTINATION = "/topic/admin";

    private final SimpMessagingTemplate messaging;

    /** The event types seen since the last flush. A set, so a burst of one type is one entry. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    @KafkaListener(groupId = "admin-realtime-${random.uuid}", topics = {
            "admin.report-events",
            "admin.moderation-events",
            "media.video-moderation-events",
            "video.video-events",
            "auth.user-events"})
    public void onEvent(ConsumerRecord<String, String> record) {
        Header eventType = record.headers().lastHeader("eventType");
        pending.add(eventType == null
                ? record.topic()
                : new String(eventType.value(), StandardCharsets.UTF_8));
    }

    @Scheduled(fixedDelayString = "${realtime.flush-interval-millis}")
    public void flush() {
        for (String type : pending) {
            // Taken one at a time as it is sent, so a record that lands after its type was taken
            // stays for the next window instead of being cleared with this one unsent.
            if (pending.remove(type)) {
                messaging.convertAndSend(DESTINATION, new AdminFrame(type));
            }
        }
    }
}
