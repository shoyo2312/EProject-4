package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ConflictException;

public class InteractionConflictException extends ConflictException {
    public InteractionConflictException(String message) {
        super("INTERACTION_CONFLICT", message);
    }
}
