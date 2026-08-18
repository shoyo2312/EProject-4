package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The provider token the client sent is expired, malformed, or was issued for a different
 * application. The message stays vague on purpose: which of the three it was is only useful to
 * someone probing us with tokens they did not get from our own sign-in button.
 */
public class InvalidSocialTokenException extends DomainException {

    public InvalidSocialTokenException() {
        super("INVALID_SOCIAL_TOKEN", "Social login token is invalid or expired", HttpStatus.UNAUTHORIZED);
    }
}
