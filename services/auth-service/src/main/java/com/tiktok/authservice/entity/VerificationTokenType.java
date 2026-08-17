package com.tiktok.authservice.entity;

public enum VerificationTokenType {
    EMAIL_VERIFICATION,
    PASSWORD_RESET,
    /**
     * Proves the person holding a provider account also holds the mailbox an account of ours
     * already sits under — the only thing that may merge the two. Its own type rather than a
     * reused EMAIL_VERIFICATION: this code is mailed to an address that is usually already
     * verified, to authorise a different action, and one must never be spendable as the other.
     */
    SOCIAL_LINK
}
