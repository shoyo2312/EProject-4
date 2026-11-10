package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ConflictException;

public class MessageRequestNotPendingException extends ConflictException {

    public MessageRequestNotPendingException() {
        super("MESSAGE_REQUEST_NOT_PENDING", "This message request is no longer pending");
    }
}
