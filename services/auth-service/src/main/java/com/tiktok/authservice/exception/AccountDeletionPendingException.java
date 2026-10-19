package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/** {@link AccountDeactivatedException} for an account whose owner asked for it to be deleted. */
public class AccountDeletionPendingException extends ForbiddenException {

    public AccountDeletionPendingException() {
        super("ACCOUNT_DELETION_PENDING", "This account is scheduled for deletion");
    }
}
