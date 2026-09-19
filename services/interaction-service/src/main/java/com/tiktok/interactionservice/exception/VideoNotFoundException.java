package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

/** Also the answer for a video that exists but the caller may not see — a distinct status would confirm it. */
public class VideoNotFoundException extends ResourceNotFoundException {
    public VideoNotFoundException(Long videoId) {
        super("VIDEO_NOT_FOUND", "Video not found: " + videoId);
    }
}
