package com.tiktok.authservice.dto.response;

import java.time.Instant;

/**
 * What the phone shows its owner before they confirm: which browser, and roughly where. That
 * screen is the defence against being handed someone else's code to scan, so it must name the
 * browser that asked, not the phone.
 *
 * @param deviceLabel fixed words only, e.g. {@code "Chrome on Windows"}; null when unknown
 * @param location    approximate place of the browser's address, or null
 * @param expiresAt   the phone's deadline to confirm
 */
public record QrLoginScanResponse(String deviceLabel, String location, Instant expiresAt) {
}
