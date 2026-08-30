package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * A Following feed request named more authors than one query will draw from.
 *
 * <p>Refused rather than truncated. The list comes from the client, so nothing bounds it but this:
 * an unbounded {@code $in} is a scan per id on every page of a feed that scrolls forever. And a
 * silently shortened list answers with a feed that is quietly missing whole creators the viewer
 * follows — a wrong feed nobody can see is wrong, which is worse than an error the caller can page
 * around.
 */
public class TooManyFollowedUsersException extends BadRequestException {

    public TooManyFollowedUsersException(int limit) {
        super("TOO_MANY_FOLLOWED_USERS",
                "A Following feed request carries at most " + limit + " authors — page the following list");
    }
}
