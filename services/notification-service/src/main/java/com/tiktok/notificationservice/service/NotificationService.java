package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.dto.response.NotificationResponse;
import com.tiktok.notificationservice.entity.NotificationType;

import java.util.List;

public interface NotificationService {

    NotificationResponse create(Long recipientId, NotificationType type, String title, String body, String referenceId);

    List<NotificationResponse> listByUser(Long recipientId);

    long unreadCount(Long recipientId);

    void markAsRead(Long requesterId, String notificationId);

    void markAllAsRead(Long requesterId);
}
