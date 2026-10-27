package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

/** No such message in this conversation (or it belongs to another one — the two are not told apart). */
public class MessageNotFoundException extends ResourceNotFoundException {

    public MessageNotFoundException() {
        super("MESSAGE_NOT_FOUND", "Message not found");
    }
}
