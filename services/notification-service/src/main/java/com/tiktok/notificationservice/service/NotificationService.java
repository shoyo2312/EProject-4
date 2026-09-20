package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.NotificationType;

import java.util.List;

public interface NotificationService {

    NotificationResponse create(Long recipientId, Long actorId, NotificationType type, String title, String body, String referenceId);

    List<NotificationResponse> listByUser(Long recipientId);

    long unreadCount(Long recipientId);

    void markAsRead(Long requesterId, String notificationId);

    void markAllAsRead(Long requesterId);

    /**
     * Records an FCM token for this account, so a notification created later has somewhere to be
     * pushed. Re-registering the same token is not an error — clients refresh tokens and replay
     * this on every launch.
     */
    void registerDevice(Long userId, String token);

    /** Silently does nothing for a token this account did not register. */
    void unregisterDevice(Long userId, String token);
}
