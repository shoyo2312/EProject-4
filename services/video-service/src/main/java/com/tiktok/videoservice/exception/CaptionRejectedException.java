package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Part of a video's caption is beyond review — the worst terms in the blocklist. Refused before
 * anything is stored, so the uploader fixes the text and publishes the same upload again.
 *
 * <p>One code per field ({@code VIDEO_TITLE_REJECTED}, {@code VIDEO_DESCRIPTION_REJECTED},
 * {@code VIDEO_TAGS_REJECTED}) because the error envelope has no slot to say which field, and a
 * client needs to know which box to put the message under. Which word it was is not said: that is
 * how a filter gets written around.
 */
public class CaptionRejectedException extends DomainException {

    public enum Field {
        TITLE, DESCRIPTION, TAGS
    }

    public CaptionRejectedException(Field field) {
        super("VIDEO_" + field.name() + "_REJECTED",
                "The video's " + field.name().toLowerCase(Locale.ROOT) + " contains language that isn't allowed",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
