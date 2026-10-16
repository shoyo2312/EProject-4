package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** A listing asked to sort by a property it does not offer — Spring Data's own answer is a 500. */
public class InvalidSortException extends BadRequestException {

    public InvalidSortException(String message) {
        super("INVALID_SORT", message);
    }
}
