package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * The {@code cursor} on a comment listing was not one this service issued.
 *
 * <p>The cursor is Cassandra's own paging state, base64'd — anything else in that parameter throws
 * out of the decoder as an IllegalArgumentException, which the handler of last resort would report
 * as INTERNAL_ERROR. A malformed query string is the client's mistake and has to read as one, or
 * it gets triaged as an outage. Mirrors video-service's InvalidFeedCursorException.
 */
public class InvalidCommentCursorException extends BadRequestException {

    public InvalidCommentCursorException() {
        super("INVALID_COMMENT_CURSOR", "Unusable comment cursor — omit it to start from the first page");
    }
}
