package com.tiktok.notificationservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.NewDeviceSignInEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Tells an account's owner it was signed into from a device it has not used before — the notice
 * TikTok files under "Account updates". auth-service decides what counts as new.
 *
 * <p>{@link NotificationType#SYSTEM}, not a type of its own: the mobile app parses the type into an
 * enum with no fallback, so a value it does not know fails its whole inbox. {@link #REFERENCE} is
 * what tells this notice from the other system ones — the web keys its "Secure your account" link
 * on it. {@code SYSTEM} never collapses repeats, which is right here: two alerts are two sign-ins.
 */
@Component
@RequiredArgsConstructor
public class NewDeviceSignInEventConsumer {

    /** Opaque to this service; the web shows the "Secure your account" link for it. */
    static final String REFERENCE = "account-security";

    static final String TITLE = "Your account logged in from a new device";

    /** Vietnam time, the way TikTok's own notice reads: "on 10/19 13:16 +07". */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.new-device-events", groupId = "notification-service")
    public void onMessage(String payload) {
        NewDeviceSignInEvent event = objectMapper.readValue(payload, NewDeviceSignInEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notificationService.create(
                        event.userId(),
                        null,
                        NotificationType.SYSTEM,
                        TITLE,
                        body(event.deviceLabel(), event.occurredAt(), event.location()),
                        REFERENCE));
    }

    /**
     * {@code "Your account logged in from device Chrome on macOS 27 on 10/23 21:42 +07 in Ho Chi
     * Minh City, Vietnam. If this wasn't you, secure your account immediately."} — "a new device"
     * when the browser named nothing known, and no " in …" when the address resolved to no place.
     */
    static String body(String deviceLabel, Instant occurredAt, String location) {
        String device = deviceLabel == null ? "a new device" : "device " + deviceLabel;
        String place = location == null ? "" : " in " + location;
        return "Your account logged in from " + device + " on " + WHEN.format(occurredAt) + " +07" + place
                + ". If this wasn't you, secure your account immediately.";
    }
}
