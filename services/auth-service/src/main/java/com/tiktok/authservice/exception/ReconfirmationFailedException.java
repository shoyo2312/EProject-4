package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * A wrong password or provider token given to confirm closing the account. The same codes a
 * sign-in answers, but 403 rather than 401: the caller is signed in, and a client reads a 401 on
 * an authenticated call as an expired session — it refreshes and sends the request again, which
 * here spends a second guess of the account's login budget.
 */
public class ReconfirmationFailedException extends ForbiddenException {

    private ReconfirmationFailedException(String code, String message) {
        super(code, message);
    }

    public static ReconfirmationFailedException wrongPassword() {
        return new ReconfirmationFailedException("INVALID_CREDENTIALS", "The password is incorrect");
    }

    public static ReconfirmationFailedException wrongSocialToken() {
        return new ReconfirmationFailedException("INVALID_SOCIAL_TOKEN",
                "That sign-in is not linked to this account, or has expired");
    }
}
