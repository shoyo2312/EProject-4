package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin warned someone. Nothing about the platform changes — the only effect a warning can
 * have is that the person is told about it, which is why this event exists at all: without it
 * WARN_USER is a row in the audit log and silence on the other end.
 *
 * <p>It carries the target of the report rather than a user id because an admin may warn over
 * any kind of report, and the thing reported is often a video, not an account. Whoever consumes
 * this resolves the target to a person; {@code targetType} is admin-service's ReportTargetType
 * spelled as a String, since event-schema does not depend on any service.
 */
public record UserWarnedEvent(
        String eventId,
        Instant occurredAt,
        String targetType,
        String targetId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static UserWarnedEvent of(String targetType, String targetId, Long adminId, String reason) {
        return new UserWarnedEvent(UUID.randomUUID().toString(), Instant.now(), targetType, targetId, adminId, reason);
    }
}
