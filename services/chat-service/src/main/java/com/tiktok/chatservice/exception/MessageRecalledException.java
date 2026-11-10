package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ConflictException;

/** A reaction arrived after the message was recalled; the recall wins. */
public class MessageRecalledException extends ConflictException {

    public MessageRecalledException() {
        super("MESSAGE_RECALLED", "This message was recalled");
    }
}
