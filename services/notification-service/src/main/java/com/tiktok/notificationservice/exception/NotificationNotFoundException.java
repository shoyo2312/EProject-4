package com.tiktok.notificationservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class NotificationNotFoundException extends ResourceNotFoundException {

    public NotificationNotFoundException(String notificationId) {
        super("NOTIFICATION_NOT_FOUND", "Notification not found: " + notificationId);
    }
}
