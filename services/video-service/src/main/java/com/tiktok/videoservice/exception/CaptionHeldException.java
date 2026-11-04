package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * An edited caption the text screen would send to review. At publish that holds the video until an
 * admin looks; an edit has no such path — the video is already live, and search and the feed would
 * carry the new words while it waited — so the edit is refused instead and the old caption stays.
 */
public class CaptionHeldException extends DomainException {

    public CaptionHeldException() {
        super("VIDEO_CAPTION_HELD",
                "This caption can't be used on a posted video. Try different wording",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
