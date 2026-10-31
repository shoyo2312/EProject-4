package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

/** The account already filters this keyword, in some capitalization. */
public class DuplicateKeywordFilterException extends ConflictException {

    public DuplicateKeywordFilterException(String keyword) {
        super("KEYWORD_FILTER_DUPLICATE", "You've already added '" + keyword + "'");
    }
}
