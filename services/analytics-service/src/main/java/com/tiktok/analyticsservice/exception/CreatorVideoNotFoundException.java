package com.tiktok.analyticsservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

/**
 * Also what someone else's video answers with: a 403 would confirm that the id exists, and Studio
 * has no reason to tell anyone that about a video that is not theirs.
 */
public class CreatorVideoNotFoundException extends ResourceNotFoundException {

    public CreatorVideoNotFoundException(String videoId) {
        super("VIDEO_NOT_FOUND", "No video " + videoId + " among your posts");
    }
}
