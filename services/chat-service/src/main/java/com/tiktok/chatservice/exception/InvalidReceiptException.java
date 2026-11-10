package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * An acknowledgement that names something the caller cannot have received or seen: a message that is
 * not in this conversation, one they sent themselves, one they deleted from their side — or no
 * message at all. Nothing is recorded.
 */
public class InvalidReceiptException extends BadRequestException {

    public InvalidReceiptException() {
        super("INVALID_RECEIPT", "That message cannot be acknowledged here");
    }
}
