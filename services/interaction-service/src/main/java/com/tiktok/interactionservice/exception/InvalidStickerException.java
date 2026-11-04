package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** A comment naming a sticker the pack does not offer: unknown, or retired. Nothing is stored. */
public class InvalidStickerException extends DomainException {
    public InvalidStickerException(String stickerId) {
        super("INVALID_STICKER", "Unknown sticker: " + stickerId, HttpStatus.BAD_REQUEST);
    }
}
