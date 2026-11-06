package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * This account cannot sign a browser in by QR: an admin, whose console takes a password and a
 * mailed code (the same reason as {@code SocialSignInPolicy}), or an account that is not open.
 */
public class QrLoginNotAllowedException extends ForbiddenException {

    public QrLoginNotAllowedException() {
        super("QR_LOGIN_NOT_ALLOWED", "This account cannot sign in with a QR code");
    }
}
