package com.tiktok.event.notification;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An inbox entry was stored for {@code recipientId}. Published by notification-service and read
 * by chat-service, which owns the only WebSocket and relays it to that user's session.
 *
 * <p>Carries the whole entry rather than an id: the relay must not call back into
 * notification-service to render a frame, and the fields are exactly the ones the REST inbox
 * already hands the client. {@code type} is the notification type's name, kept as a String so
 * this record does not depend on a service-local enum.
 */
public record NotificationCreatedEvent(
        String eventId,
        Instant occurredAt,
        Long recipientId,
        String notificationId,
        String type,
        String title,
        String body,
        String referenceId,
        Instant createdAt
) implements DomainEvent {

    public static NotificationCreatedEvent of(
            Long recipientId, String notificationId, String type,
            String title, String body, String referenceId, Instant createdAt) {
        return new NotificationCreatedEvent(
                UUID.randomUUID().toString(), Instant.now(),
                recipientId, notificationId, type, title, body, referenceId, createdAt);
    }
}
