package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class InvalidCursorException extends BadRequestException {

    public InvalidCursorException(String cursor) {
        super("INVALID_CURSOR", "Cursor is not a valid page cursor: " + cursor);
    }
}
