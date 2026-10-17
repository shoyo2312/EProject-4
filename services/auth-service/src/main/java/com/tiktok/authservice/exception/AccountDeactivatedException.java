package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The credential was right, and the account is one its owner closed. Distinct from
 * {@link InvalidCredentialsException} for the reason {@link EmailNotVerifiedException} is: the
 * caller has proven it may open the account, so there is nothing left to hide, and the client
 * offers to reopen it by sending the same sign-in again with {@code reactivate}.
 */
public class AccountDeactivatedException extends ForbiddenException {

    public AccountDeactivatedException() {
        super("ACCOUNT_DEACTIVATED", "This account is deactivated");
    }
}
