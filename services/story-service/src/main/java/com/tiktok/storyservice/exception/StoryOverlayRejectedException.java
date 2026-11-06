package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The words laid over the picture in the editor hold one of the worst terms in the blocklist —
 * the caption's rule, for text a viewer reads just the same. Refused before anything is stored.
 */
public class StoryOverlayRejectedException extends DomainException {

    public StoryOverlayRejectedException() {
        super("STORY_OVERLAY_REJECTED", "The text on the story contains language that isn't allowed",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
