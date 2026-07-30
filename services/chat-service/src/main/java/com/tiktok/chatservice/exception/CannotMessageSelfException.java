package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class CannotMessageSelfException extends BadRequestException {

    public CannotMessageSelfException() {
        super("CANNOT_MESSAGE_SELF", "A user cannot start a conversation with themselves");
    }
}
