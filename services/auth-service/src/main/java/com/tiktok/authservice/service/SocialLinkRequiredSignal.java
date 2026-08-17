package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;

/**
 * Internal, never leaves the service layer: {@link SocialAccountRegistrar} found that the address
 * belongs to {@code owner} and the provider does not vouch for it, so registration must not
 * proceed. {@link OAuthServiceImpl} turns it into a mailed challenge and a 409.
 *
 * <p>An exception rather than a return value because it has to abort the registrar's transaction:
 * the challenge is mailed from a transaction of its own, and nothing the registrar was part-way
 * through may survive.
 */
class SocialLinkRequiredSignal extends RuntimeException {

    private final transient User owner;

    SocialLinkRequiredSignal(User owner) {
        // No message, no stack trace: this is control flow, not a fault, and it is thrown on a
        // path any user can trigger at will.
        super(null, null, false, false);
        this.owner = owner;
    }

    User owner() {
        return owner;
    }
}
