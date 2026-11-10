package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** The emoji is not one of the offered reactions. */
public class InvalidReactionException extends BadRequestException {

    public InvalidReactionException() {
        super("INVALID_REACTION", "That reaction is not available");
    }
}
