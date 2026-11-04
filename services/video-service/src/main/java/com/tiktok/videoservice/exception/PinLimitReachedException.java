package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.ConflictException;

/** A profile shows at most three pinned videos, as on TikTok. */
public class PinLimitReachedException extends ConflictException {

    public PinLimitReachedException(int limit) {
        super("PIN_LIMIT_REACHED", "You can pin up to " + limit + " videos. Unpin one first");
    }
}
