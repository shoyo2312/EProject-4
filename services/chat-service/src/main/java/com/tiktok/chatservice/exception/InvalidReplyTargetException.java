package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** The message being replied to is missing, in another conversation, recalled, or hidden by the sender. */
public class InvalidReplyTargetException extends BadRequestException {

    public InvalidReplyTargetException() {
        super("REPLY_TARGET_UNAVAILABLE", "That message can no longer be replied to");
    }
}
