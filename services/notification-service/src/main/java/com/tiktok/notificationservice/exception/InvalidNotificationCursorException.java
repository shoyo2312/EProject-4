package com.tiktok.notificationservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * {@code before} named no entry of the caller's own inbox. The same answer whether the id does not
 * exist or belongs to someone else, so a cursor cannot be used to probe other inboxes.
 */
public class InvalidNotificationCursorException extends BadRequestException {

    public InvalidNotificationCursorException(String before) {
        super("INVALID_CURSOR", "Not a notification in your inbox: " + before);
    }
}
