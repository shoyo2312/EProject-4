package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The account behind this provider sign-in may only be signed in to with its password and the
 * admin second factor — see {@code SocialSignInPolicy}.
 *
 * <p>The message does not say why. The caller holds a provider account pointed at this address,
 * which is not proof of the mailbox, and "this is an admin account" is not theirs to learn.
 */
public class SocialSignInNotAllowedException extends ForbiddenException {

    public SocialSignInNotAllowedException() {
        super("SOCIAL_SIGN_IN_NOT_ALLOWED", "This account signs in with its password");
    }
}
