package com.tiktok.notificationservice.dto.response;

import com.tiktok.notificationservice.entity.NotificationType;

import java.time.Instant;

public record NotificationResponse(
        String id,
        NotificationType type,
        String title,
        String body,
        String referenceId,
        boolean read,
        Instant createdAt
) {
}
