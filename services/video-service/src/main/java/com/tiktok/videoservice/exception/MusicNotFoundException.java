package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.BadRequestException;

/**
 * The edit names a track the catalogue does not have, or has but would not offer — one that
 * cannot be downloaded or may not be put to a video.
 */
public class MusicNotFoundException extends BadRequestException {

    public MusicNotFoundException() {
        super("MUSIC_NOT_FOUND", "That sound is not available");
    }
}
