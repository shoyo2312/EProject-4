package com.tiktok.chatservice.exception;

import com.tiktok.chatservice.entity.ChatUserPrefs;
import com.tiktok.common.exception.ConflictException;

/** The viewer already has the most conversations pinned they can; nothing is unpinned on their behalf. */
public class PinLimitReachedException extends ConflictException {

    public PinLimitReachedException() {
        super("PIN_LIMIT_REACHED",
                "You can pin at most " + ChatUserPrefs.MAX_PINS + " conversations. Unpin one first");
    }
}
