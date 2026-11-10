package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/** Recalling is the author's alone; the other participant can only hide a message from their own side. */
public class NotMessageAuthorException extends ForbiddenException {

    public NotMessageAuthorException() {
        super("NOT_MESSAGE_AUTHOR", "Only the sender can recall a message");
    }
}
