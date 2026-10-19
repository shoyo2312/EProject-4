package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** A term no service could use as asked: nothing to match, no category, or nothing to exempt. */
public class InvalidModerationTermException extends BadRequestException {

    public InvalidModerationTermException(String message) {
        super("INVALID_MODERATION_TERM", message);
    }
}
