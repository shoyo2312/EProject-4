package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.ConflictException;

/** A live term already keys the same — the matcher would read the two as one. */
public class ModerationTermExistsException extends ConflictException {

    public ModerationTermExistsException(String term) {
        super("MODERATION_TERM_EXISTS", "A term matching '" + term + "' is already on the list");
    }
}
