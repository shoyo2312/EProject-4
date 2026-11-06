package com.tiktok.authservice.dto.response;

import java.time.Instant;

/**
 * A fresh QR sign-in for the browser to show.
 *
 * @param qrId       names the code; the only part the QR image carries
 * @param pollToken  the browser's proof that it is the one that asked for the code. Never put in
 *                   the image: whoever can read the screen must not be able to collect the session
 * @param qrContent  the exact text to encode in the image, so the format lives in one place
 * @param expiresAt  when the code stops working if nobody scans it
 */
public record QrLoginCreatedResponse(String qrId, String pollToken, String qrContent, Instant expiresAt) {
}
