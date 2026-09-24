package com.tiktok.chatservice.client;

/**
 * The account-level answer to "who may start a conversation with me", as user-service holds it.
 * A copy of user-service's enum — there is no API-schema library between these services — and a
 * name this service cannot map is refused rather than assumed, see
 * {@link com.tiktok.chatservice.exception.DmSettingsUnavailableException}.
 */
public enum DmAudience {
    EVERYONE,
    FRIENDS,
    NO_ONE
}
