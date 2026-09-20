package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.notification.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Relays inbox entries written by notification-service onto the socket this service owns.
 *
 * <p>Unlike the stats fanouts there is nothing to re-read and nothing to batch: the event already
 * carries the whole entry, and a notification is an event in its own right rather than a snapshot
 * a later one supersedes, so it goes out immediately.
 *
 * <p>{@code convertAndSendToUser} resolves to the recipient's own session queue, so a client can
 * only ever receive its own — see {@code StompSubscriptionInterceptor}. A recipient with no open
 * session simply gets nothing; they read the entry from the REST inbox next time they open the
 * drawer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationFanout {

    static final String DESTINATION = "/queue/notifications";

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "notification.created")
    public void onNotificationCreated(String payload) {
        NotificationCreatedEvent event;
        try {
            event = objectMapper.readValue(payload, NotificationCreatedEvent.class);
        } catch (Exception e) {
            log.warn("Unreadable notification event dropped: {}", e.getMessage());
            return;
        }
        if (event.recipientId() == null) {
            return;
        }
        messaging.convertAndSendToUser(String.valueOf(event.recipientId()), DESTINATION, event);
    }
}
