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
    SOCIAL_LINK,

    /**
     * Second factor for admin-console sign-in: the account passed its password and is an ADMIN,
     * so a fresh code is mailed and no session is issued until it comes back. Its own type for
     * the same reason as SOCIAL_LINK — mailed to an already-verified address to authorise a
     * different action, and never interchangeable with an email-verification code.
     */
    ADMIN_LOGIN
}
