package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * The raw file being published already has a video.
 *
 * <p>One upload is one video. Without this, a client that retries POST /videos after a timeout —
 * or simply sends the same key twice — gets a second Video document, a second
 * VideoPublishedEvent, and a second transcode job off one file, with no way for anything
 * downstream to tell the copies apart.
 *
 * <p>Raised from the unique index rather than from a prior existence check, because a check
 * followed by an insert lets two concurrent requests both pass the check.
 */
public class AlreadyPublishedException extends BadRequestException {

    public AlreadyPublishedException() {
        super("RAW_FILE_ALREADY_PUBLISHED", "this upload has already been published");
    }
}
