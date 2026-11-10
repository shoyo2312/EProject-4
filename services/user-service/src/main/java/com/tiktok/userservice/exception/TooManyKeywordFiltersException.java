package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class TooManyKeywordFiltersException extends ConflictException {

    public TooManyKeywordFiltersException(int max) {
        super("TOO_MANY_KEYWORD_FILTERS", "You can filter at most " + max + " keywords");
    }
}
