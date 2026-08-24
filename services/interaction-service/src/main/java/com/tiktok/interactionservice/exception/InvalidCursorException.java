package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * The {@code cursor} on a likes/saves listing was not one this service issued. Same reasoning as
 * {@link InvalidCommentCursorException} — a query-string typo has to read as a 400, not as the
 * INTERNAL_ERROR the catch-all would report for the decoder's IllegalArgumentException.
 */
public class InvalidCursorException extends BadRequestException {

    public InvalidCursorException() {
        super("INVALID_CURSOR", "Unusable cursor — omit it to start from the first page");
    }
}
