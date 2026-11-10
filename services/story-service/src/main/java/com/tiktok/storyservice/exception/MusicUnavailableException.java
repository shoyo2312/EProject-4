package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The song could not be checked: video-service or the catalogue behind it did not answer. Not the
 * caller's fault, so 503; the same story posts once the catalogue is back, or without the song.
 */
public class MusicUnavailableException extends DomainException {

    public MusicUnavailableException(Throwable cause) {
        super("MUSIC_UNAVAILABLE", "Music is not available right now", HttpStatus.SERVICE_UNAVAILABLE);
        if (cause != null) {
            initCause(cause);
        }
    }
}
