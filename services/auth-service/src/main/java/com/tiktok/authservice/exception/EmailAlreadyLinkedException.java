package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The account already has a verified address, so it is not the one this endpoint exists for.
 * Changing a verified address is a different operation with a different threat model — it has to
 * prove control of the new address <em>and</em> tell the old one — and does not exist yet.
 */
public class EmailAlreadyLinkedException extends DomainException {

    public EmailAlreadyLinkedException() {
        super("EMAIL_ALREADY_LINKED", "This account already has a verified email address",
                HttpStatus.CONFLICT);
    }
}
