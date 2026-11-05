package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The music catalogue could not be read: not configured, unreachable, or refusing the query. Not
 * the caller's fault, so 503 — the app says music is unavailable and the rest of the editor works.
 */
public class MusicUnavailableException extends DomainException {

    public MusicUnavailableException(Throwable cause) {
        super("MUSIC_UNAVAILABLE", "Music is not available right now", HttpStatus.SERVICE_UNAVAILABLE);
        if (cause != null) {
            initCause(cause);
        }
    }
}
