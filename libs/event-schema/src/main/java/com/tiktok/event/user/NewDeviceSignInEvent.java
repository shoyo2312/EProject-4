package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An account was signed into from a device it has not used before. auth-service decides that
 * (KnownDevices) inside the sign-in's own transaction; notification-service tells the owner —
 * "Your account logged in from a new device".
 *
 * <p>Its own topic, {@code auth.new-device-events}, for the reason {@link UsernameChangedEvent}
 * has one: consumers of {@code auth.user-events} read every message there as a registration and
 * would welcome the account again for each alert.
 *
 * @param occurredAt  when the sign-in happened, which the notice states in Vietnam time
 * @param deviceLabel the browser and the platform in fixed words ({@code "Chrome on macOS 27"}),
 *                    never the client-hint model: it goes into a message the owner reads, and the
 *                    hints are the client's to write. Null when the agent named nothing known
 * @param location    the approximate place of the sign-in's address, or null
 */
public record NewDeviceSignInEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String deviceLabel,
        String location
) implements DomainEvent {

    public static NewDeviceSignInEvent of(Long userId, String deviceLabel, String location) {
        return new NewDeviceSignInEvent(UUID.randomUUID().toString(), Instant.now(), userId, deviceLabel, location);
    }
}
