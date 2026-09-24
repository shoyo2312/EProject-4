package com.tiktok.authservice.dto.response;

import com.tiktok.authservice.entity.SecurityEventType;

import java.time.Instant;

/**
 * One entry of an account's security log, as the admin console reads it.
 *
 * <p>Both the label and the raw agent are sent: the label is what a reviewer scans a list by, and
 * the agent is what they fall back to when the label says something vague and the exact client
 * matters.
 *
 * @param ipAddress   the address the request came from, or null when nothing HTTP caused the entry
 * @param deviceLabel {@code "Chrome on macOS"}, or null when the agent named nothing recognised
 * @param detail      a short phrase, or null — never a secret
 */
public record SecurityEventResponse(
        Long id,
        SecurityEventType eventType,
        String ipAddress,
        String userAgent,
        String deviceLabel,
        String detail,
        Instant createdAt
) {
}
