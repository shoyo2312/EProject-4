package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.NotificationType;

import java.util.List;

public interface NotificationService {

    /** Also the page size when the client names none: the inbox used to come back whole. */
    int MAX_PAGE_SIZE = 50;

    NotificationResponse create(Long recipientId, Long actorId, NotificationType type, String title, String body, String referenceId);

    /**
     * One page of the caller's inbox, newest first.
     *
     * @param before the id of the last entry of the previous page, or null for the newest
     * @param size   how many to return, clamped to 1..{@code MAX_PAGE_SIZE}; fewer means the end
     */
    List<NotificationResponse> listByUser(Long recipientId, String before, int size);

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
