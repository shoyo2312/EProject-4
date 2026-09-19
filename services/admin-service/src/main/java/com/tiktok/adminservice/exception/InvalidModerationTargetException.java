package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class InvalidModerationTargetException extends BadRequestException {

    public InvalidModerationTargetException(String message) {
        super("INVALID_MODERATION_TARGET", message);
    }
}
