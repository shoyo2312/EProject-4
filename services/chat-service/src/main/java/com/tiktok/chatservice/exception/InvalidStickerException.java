package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** The sticker id is missing, unknown, or no longer offered for sending. Nothing was stored or changed. */
public class InvalidStickerException extends BadRequestException {

    public InvalidStickerException() {
        super("INVALID_STICKER", "That sticker is not available");
    }
}
