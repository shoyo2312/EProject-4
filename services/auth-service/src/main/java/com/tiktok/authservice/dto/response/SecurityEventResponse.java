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
 * @param deviceLabel {@code "Chrome on macOS 15.1"}, or null when the agent named nothing known
 * @param deviceModel the hardware the browser admitted to, or null — which is the normal case,
 *                    since only Chromium sends the hint and it is empty on every desktop
 * @param location    the approximate place of {@code ipAddress}, looked up when the entry was
 *                    written ({@code "Ho Chi Minh City, Vietnam"}, sometimes the country alone),
 *                    or null — for older entries and addresses that resolve to nothing
 * @param detail      a short phrase, or null — never a secret
 */
public record SecurityEventResponse(
        Long id,
        SecurityEventType eventType,
        String ipAddress,
        String userAgent,
        String deviceLabel,
        String deviceModel,
        String location,
        String detail,
        Instant createdAt
) {
}
