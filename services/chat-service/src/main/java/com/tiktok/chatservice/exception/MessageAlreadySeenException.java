package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ConflictException;

/**
 * A recall for everyone, after the other side has already read the message. Taking it back would
 * only hide what they have seen; the sender can still delete it from their own side.
 */
public class MessageAlreadySeenException extends ConflictException {

    public MessageAlreadySeenException() {
        super("MESSAGE_ALREADY_SEEN", "This message has already been seen and can no longer be recalled");
    }
}
