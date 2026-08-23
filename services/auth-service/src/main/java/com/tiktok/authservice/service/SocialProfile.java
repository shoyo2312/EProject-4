package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.AuthProvider;

/**
 * What a provider tells us about the account behind a token it issued, after the token has been
 * checked against our own app id.
 *
 * @param uid           the provider's immutable id for the account — Google's {@code sub},
 *                      Facebook's numeric id. This, not the email, is what identifies a returning
 *                      user; an address can change hands at the provider, the uid cannot.
 * @param email         null when the provider gave none. Facebook's {@code email} permission is
 *                      optional and an account registered with a phone number has no address.
 * @param emailVerified whether the provider asserts it has verified the address. Only a verified
 *                      address may be auto-linked to an existing account of ours — otherwise
 *                      anyone who can register {@code victim@example.com} at a provider takes over
 *                      the account we already hold under that address. Facebook asserts nothing,
 *                      so this is always false for it.
 * @param avatarUrl     the profile picture the provider publishes for the account, null when it
 *                      gave none. Only ever used to seed a brand new profile: an avatar the user
 *                      has picked since is theirs, and no later login overwrites it.
 */
public record SocialProfile(
        AuthProvider provider,
        String uid,
        String email,
        boolean emailVerified,
        String avatarUrl
) {

    /** For the callers — mostly tests — that have no picture to state. */
    public SocialProfile(AuthProvider provider, String uid, String email, boolean emailVerified) {
        this(provider, uid, email, emailVerified, null);
    }
}
