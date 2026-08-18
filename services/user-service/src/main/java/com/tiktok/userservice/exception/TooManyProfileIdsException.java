package com.tiktok.userservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * The batch profile lookup was asked for more ids than one request may carry. The cap is a trust
 * boundary, not a preference: {@code ids} arrives straight off the query string and lands in an
 * {@code IN (...)} list, so without it one caller turns a single request into an unbounded query.
 */
public class TooManyProfileIdsException extends BadRequestException {

    public TooManyProfileIdsException(int max) {
        super("TOO_MANY_PROFILE_IDS", "At most " + max + " user ids per request");
    }
}
