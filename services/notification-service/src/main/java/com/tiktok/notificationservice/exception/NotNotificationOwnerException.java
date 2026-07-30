package com.tiktok.notificationservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotNotificationOwnerException extends ForbiddenException {

    public NotNotificationOwnerException(String notificationId) {
        super("NOT_NOTIFICATION_OWNER", "You do not own notification: " + notificationId);
    }
}
