package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

/** No such filter on the caller's account — also the answer for someone else's id. */
public class KeywordFilterNotFoundException extends ResourceNotFoundException {

    public KeywordFilterNotFoundException(Long id) {
        super("KEYWORD_FILTER_NOT_FOUND", "Keyword filter " + id + " not found");
    }
}
