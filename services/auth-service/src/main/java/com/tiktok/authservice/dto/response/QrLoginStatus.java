package com.tiktok.authservice.dto.response;

/**
 * Where a QR sign-in stands, as the browser that showed the code sees it.
 *
 * <p>{@code EXPIRED} also answers a wrong poll token: telling "no such code" apart from "not your
 * code" would only help someone guessing at codes they did not create.
 */
public enum QrLoginStatus {
    /** Shown, not scanned yet. */
    PENDING,
    /** A phone scanned it and is asking its owner to confirm. */
    SCANNED,
    /** Confirmed on the phone; this answer carries the browser's session, once. */
    APPROVED,
    /** The phone's owner said no. */
    REJECTED,
    /** Gone: timed out, already spent, or never existed. */
    EXPIRED
}
