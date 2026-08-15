package com.tiktok.authservice.dto.response;

/**
 * @param requiresEmail the account has no email address, so the client should ask for one. It is
 *                      not an error and the session is fully usable without it, but until an
 *                      address exists the account cannot reset a password or be reached at all —
 *                      losing access to the Facebook account would mean losing this one.
 */
public record SocialLoginResponse(
        TokenResponse tokens,
        boolean requiresEmail
) {
}
