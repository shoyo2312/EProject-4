package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** The message's fields contradict its type (a sticker with text, text with a sticker id). */
public class InvalidMessageException extends BadRequestException {

    public InvalidMessageException(String message) {
        super("INVALID_MESSAGE", message);
    }
}
