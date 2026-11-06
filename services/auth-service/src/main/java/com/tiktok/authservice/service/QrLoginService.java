package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.QrLoginCreatedResponse;
import com.tiktok.authservice.dto.response.QrLoginScanResponse;
import com.tiktok.authservice.dto.response.QrLoginStatusResponse;

/**
 * Signing a browser in by scanning a code with a phone that is already signed in.
 *
 * <p>The browser creates the code and polls it; the phone scans it, shows its owner which browser
 * is asking, and approves or rejects. The session is minted on the browser's own poll, so it is
 * recorded as that browser — its device type, address and new-device alert — not as the phone.
 */
public interface QrLoginService {

    /** A new code for the browser making this request. 429 when one address asks too often. */
    QrLoginCreatedResponse create();

    /** The browser's poll. On approval this returns the session, once, and the code is gone. */
    QrLoginStatusResponse poll(String qrId, String pollToken);

    /** The phone claims the code for its account and learns which browser it is for. */
    QrLoginScanResponse scan(String qrId, Long userId);

    void approve(String qrId, Long userId);

    void reject(String qrId, Long userId);
}
