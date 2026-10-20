package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The caption holds one of the worst terms in the blocklist. Refused before anything is stored, so
 * the same upload can be posted again once the text is fixed. Which word it was is not said.
 */
public class StoryCaptionRejectedException extends DomainException {

    public StoryCaptionRejectedException() {
        super("STORY_CAPTION_REJECTED", "The story's caption contains language that isn't allowed",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
