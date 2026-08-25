package com.tiktok.userservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class InvalidAvatarException extends BadRequestException {

    public InvalidAvatarException(String message) {
        super("INVALID_AVATAR", message);
    }
}
