package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class MessageRequestNotAcceptedException extends ForbiddenException {

    public MessageRequestNotAcceptedException() {
        super("MESSAGE_REQUEST_NOT_ACCEPTED", "Accept this message request before replying");
    }
}
