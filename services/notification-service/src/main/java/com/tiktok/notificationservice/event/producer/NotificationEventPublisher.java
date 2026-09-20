package com.tiktok.notificationservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.notification.NotificationCreatedEvent;
import com.tiktok.notificationservice.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces a stored inbox entry so chat-service can push it down the user's socket.
 *
 * <p>No outbox, and no waiting for the broker: the write this follows is a single Mongo save with
 * no transaction to enlist in, and the entry itself is the notification — a lost announcement
 * costs a live badge update, which the next inbox fetch repairs. Failing the caller instead would
 * send the consumer back for a redelivery that writes the entry a second time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventPublisher {

    public static final String TOPIC = "notification.created";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishCreated(Notification notification) {
        NotificationCreatedEvent event = NotificationCreatedEvent.of(
                notification.getRecipientId(),
                notification.getId(),
                notification.getActorId(),
                notification.getType().name(),
                notification.getTitle(),
                notification.getBody(),
                notification.getReferenceId(),
                notification.getCreatedAt());
        try {
            kafkaTemplate.send(TOPIC, String.valueOf(notification.getRecipientId()),
                    objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Notification {} was stored but not announced: {}", notification.getId(), e.getMessage());
        }
    }
}
