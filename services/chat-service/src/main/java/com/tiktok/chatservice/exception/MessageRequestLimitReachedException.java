package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class MessageRequestLimitReachedException extends ForbiddenException {

    public MessageRequestLimitReachedException() {
        super("MESSAGE_REQUEST_LIMIT_REACHED", "You can send more messages once this request is accepted");
    }
}
