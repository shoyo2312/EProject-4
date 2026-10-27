package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotRequestRecipientException extends ForbiddenException {

    public NotRequestRecipientException() {
        super("NOT_REQUEST_RECIPIENT", "Only the recipient of a message request can accept or delete it");
    }
}
