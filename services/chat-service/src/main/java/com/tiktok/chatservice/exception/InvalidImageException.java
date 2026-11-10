package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** The upload is not a picture this service accepts: wrong type, undecodable, or over a limit. */
public class InvalidImageException extends BadRequestException {

    public InvalidImageException(String code, String message) {
        super(code, message);
    }
}
