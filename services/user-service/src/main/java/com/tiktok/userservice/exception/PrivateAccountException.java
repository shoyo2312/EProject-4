package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * A private account's followers/following list, asked for by someone who neither owns nor follows
 * it. A 403 where a block answers 404: that the account is private is already on its profile, so
 * the status confirms nothing.
 */
public class PrivateAccountException extends ForbiddenException {

    public PrivateAccountException() {
        super("PRIVATE_ACCOUNT", "This account is private");
    }
}
