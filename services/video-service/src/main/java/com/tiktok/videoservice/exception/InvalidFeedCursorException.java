package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * The {@code cursor} on a feed request was not one this service issued.
 *
 * <p>Answered rather than ignored. Silently restarting from the top would hand a client stuck on a
 * corrupted cursor an endless first page with no way to notice, which is the kind of bug that gets
 * blamed on the feed ranking for a week.
 */
public class InvalidFeedCursorException extends BadRequestException {

    public InvalidFeedCursorException() {
        super("INVALID_FEED_CURSOR", "Unusable feed cursor — omit it to start from the newest video");
    }
}
