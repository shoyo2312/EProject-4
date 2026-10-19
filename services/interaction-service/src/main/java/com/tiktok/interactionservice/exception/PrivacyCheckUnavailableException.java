package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * user-service could not say whether an account is private, so its Reposts tab stays shut — but
 * not as {@link PrivateAccountException}. That 403 tells the client the account is private, which
 * for most accounts is untrue, and during an outage it turned every public profile on the
 * platform into a locked one. This one says the answer is missing, and that retrying can fix it.
 */
public class PrivacyCheckUnavailableException extends DomainException {

    public PrivacyCheckUnavailableException(Throwable cause) {
        super("PRIVACY_CHECK_UNAVAILABLE", "Could not load these reposts right now, please try again",
                HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
