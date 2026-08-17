package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The provider account is new to us, but the address behind it already belongs to an account of
 * ours and the provider does not vouch for that address (Facebook never does). Signing in would
 * mean either handing over someone else's account on an unverified claim, or silently creating a
 * second account for the same person — so it does neither.
 *
 * <p>A code has been mailed to the address by the time this is thrown. The client's next move is
 * {@code POST /auth/oauth/{provider}/link} with that code and the same provider token, which
 * proves both halves — the mailbox and the provider account — and merges them.
 *
 * <p>The address itself is not in the message. The client already knows which provider account it
 * signed in with, and it must not be told which of our accounts sits under that address: to an
 * attacker holding a provider account carrying someone else's address, that would confirm the
 * victim has an account here.
 */
public class SocialLinkVerificationRequiredException extends DomainException {

    public SocialLinkVerificationRequiredException() {
        super("SOCIAL_LINK_VERIFICATION_REQUIRED",
                "Enter the code we emailed to confirm this address is yours",
                HttpStatus.CONFLICT);
    }
}
