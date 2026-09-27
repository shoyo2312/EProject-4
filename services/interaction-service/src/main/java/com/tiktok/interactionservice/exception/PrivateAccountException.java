package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The Reposts tab of a private account, asked for by someone who neither owns nor follows it.
 * A 403 with its own code, so the client can say "This account is private" instead of an empty tab.
 */
public class PrivateAccountException extends ForbiddenException {

    public PrivateAccountException() {
        super("PRIVATE_ACCOUNT", "This account is private");
    }
}
