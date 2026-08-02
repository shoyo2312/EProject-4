package com.tiktok.userservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class CannotBlockSelfException extends BadRequestException {

    public CannotBlockSelfException() {
        super("CANNOT_BLOCK_SELF", "A user cannot block themselves");
    }
}
