package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.exception.SocialSignInNotAllowedException;

/**
 * Which accounts a provider sign-in may reach. Today that is every account but an ADMIN.
 *
 * <p>An admin console session costs a password, Turnstile and an emailed code (see
 * {@link AdminMfaService}). A provider sign-in costs none of those: Google vouching for the address
 * links straight to the account, and a Facebook account carrying the address gets there with the
 * link code mailed to it. Either way the mailbox alone was enough — the password was never asked
 * for — which turns the admin's two factors into one, and into the one most often phished.
 *
 * <p>Checked wherever a provider sign-in resolves to an account, before anything is written: a
 * link attached to an admin, even one never used to sign in, is a standing invitation to try.
 * Refusing rather than routing the admin through the second factor keeps one way into the console.
 */
final class SocialSignInPolicy {

    private SocialSignInPolicy() {
    }

    static void requireAllowed(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            throw new SocialSignInNotAllowedException();
        }
    }
}
