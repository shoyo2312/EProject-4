package com.tiktok.userservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class CannotMuteSelfException extends BadRequestException {

    public CannotMuteSelfException() {
        super("CANNOT_MUTE_SELF", "A user cannot mute themselves");
    }
}
