package com.tiktok.chatservice.realtime;

import com.tiktok.event.notification.NotificationCreatedEvent;

/**
 * One message on {@code /user/queue/notifications}. Same fields the REST inbox hands the client,
 * minus {@code read} — an entry is unread the moment it is written.
 *
 * <p>Exists so the ids leave as Strings, like every other frame in this package: the Kafka event
 * carries them as {@code Long}, and a Snowflake sent as a JSON number is rounded by
 * {@code JSON.parse} on the way in. The client then resolved an actor id that belongs to nobody,
 * got an empty answer from user-service, and rendered the notification with no name and no
 * avatar — the REST inbox was unaffected, because that path parses ids losslessly.
 */
public record NotificationFrame(
        String notificationId,
        String recipientId,
        String actorId,
        String type,
        String title,
        String body,
        String referenceId,
        String createdAt
) {

    public static NotificationFrame of(NotificationCreatedEvent event) {
        return new NotificationFrame(
                event.notificationId(),
                String.valueOf(event.recipientId()),
                event.actorId() == null ? null : String.valueOf(event.actorId()),
                event.type(),
                event.title(),
                event.body(),
                event.referenceId(),
                event.createdAt() == null ? null : event.createdAt().toString());
    }
}
