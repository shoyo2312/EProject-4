package com.tiktok.authservice.dto.request;

/**
 * The one username rule, for every request that sets one: the web client's — lowercase letters,
 * digits, underscores and periods, starting with a letter. The handle becomes the
 * {@code /@handle} profile link, and starting with a letter keeps it from ever looking like the
 * numeric id the client routes with.
 *
 * <p>No "@" is the part sign-in depends on. The two signups used to take any 3-50 characters, so
 * "victim@example.com" was a username anyone could register, and it answered for that address
 * at sign-in — see {@code UserRepository.findSignInAccount}.
 */
final class UsernameRule {

    static final String REGEXP = "^[a-z][a-z0-9._]{2,49}$";
    static final String MESSAGE =
            "must be 3-50 lowercase letters, digits, underscores or periods, starting with a letter";

    private UsernameRule() {
    }
}
