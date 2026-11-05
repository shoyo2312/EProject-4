package com.tiktok.authservice.dto.response;

/**
 * One poll's answer.
 *
 * @param status   see {@link QrLoginStatus}
 * @param username the account that scanned the code, from {@code SCANNED} on — so the browser can
 *                 show whose phone is about to sign it in. Null before that
 * @param tokens   the browser's new session, only on the single {@code APPROVED} answer
 */
public record QrLoginStatusResponse(QrLoginStatus status, String username, TokenResponse tokens) {

    public static QrLoginStatusResponse of(QrLoginStatus status) {
        return new QrLoginStatusResponse(status, null, null);
    }
}
