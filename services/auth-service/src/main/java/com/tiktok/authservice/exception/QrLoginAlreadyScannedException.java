package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ConflictException;

/** Another account scanned this code first. A code signs in at most one account. */
public class QrLoginAlreadyScannedException extends ConflictException {

    public QrLoginAlreadyScannedException() {
        super("QR_LOGIN_ALREADY_SCANNED", "This QR code has already been scanned");
    }
}
