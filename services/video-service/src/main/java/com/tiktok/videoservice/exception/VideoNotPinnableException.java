package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.ConflictException;

/**
 * Only a published video that is not private can be pinned: the pin is for the profile, and
 * anything else shows there to nobody but the owner while taking one of the three slots.
 */
public class VideoNotPinnableException extends ConflictException {

    public VideoNotPinnableException(String videoId) {
        super("VIDEO_NOT_PINNABLE", "Only a published video that is not private can be pinned: " + videoId);
    }
}
