package com.tiktok.authservice.entity;

/**
 * What happened. Stored as the name, not the ordinal, so inserting a value here never rewrites
 * the meaning of rows already on disk.
 *
 * <p>Deliberately absent: token refreshes, which happen every fifteen minutes per signed-in
 * client and would outnumber everything else here by two orders of magnitude to say a session the
 * log already knows about is still alive — see {@code TokenIssuer.reissue}. Also absent are bans
 * and unbans: those are moderation decisions, already recorded with their admin and their reason
 * in admin-service's audit log, and writing them here too would give one decision two entries in
 * the console's timeline.
 */
public enum SecurityEventType {

    LOGIN_SUCCEEDED,

    /**
     * A password that did not match, on an account that exists. An attempt against an identifier
     * that resolves to no account is not recorded — there is no account whose history it belongs
     * to, and creating one keyed on the typed string would make the log itself a way to find out
     * which usernames are real.
     */
    LOGIN_FAILED,

    LOGOUT,

    PASSWORD_RESET_REQUESTED,

    PASSWORD_RESET_COMPLETED,

    /** An address was set or replaced. The new address is the event's detail. */
    EMAIL_CHANGED,

    EMAIL_VERIFIED,

    /** An admin sign-in reached the second factor and a code was mailed. */
    MFA_CHALLENGED,

    /** An admin chose to skip that second factor on this browser for the next thirty days. */
    DEVICE_REMEMBERED,

    /** Every session for the account was ended at once — a reset, or a detected token replay. */
    SESSIONS_REVOKED
}
