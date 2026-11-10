package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

/** Never parked there, or retention has already dropped it. */
public class DeadLetterNotFoundException extends ResourceNotFoundException {

    public DeadLetterNotFoundException(String coordinates) {
        super("DEAD_LETTER_NOT_FOUND", "No dead-letter record at " + coordinates);
    }
}
