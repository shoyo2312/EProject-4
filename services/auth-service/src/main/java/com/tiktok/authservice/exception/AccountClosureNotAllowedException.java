package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * Admin accounts do not close themselves, for the reason they are not banned: the console must not
 * lose its last admin, nor one stolen admin session take an admin away.
 */
public class AccountClosureNotAllowedException extends ForbiddenException {

    public AccountClosureNotAllowedException() {
        super("ACCOUNT_CLOSURE_NOT_ALLOWED", "Admin accounts cannot be closed");
    }
}
