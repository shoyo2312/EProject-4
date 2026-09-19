package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class MessagingBlockedException extends ForbiddenException {

    public MessagingBlockedException() {
        super("MESSAGING_BLOCKED", "You cannot message this user");
    }
}
