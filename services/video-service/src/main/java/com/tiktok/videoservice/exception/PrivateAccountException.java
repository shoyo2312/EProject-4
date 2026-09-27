package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The profile grid of a private account, asked for by someone who neither owns nor follows it. A
 * 403 rather than the 404 a hidden video gets: the account's privacy is already on its profile,
 * so the status gives nothing away.
 */
public class PrivateAccountException extends ForbiddenException {

    public PrivateAccountException() {
        super("PRIVATE_ACCOUNT", "This account is private");
    }
}
