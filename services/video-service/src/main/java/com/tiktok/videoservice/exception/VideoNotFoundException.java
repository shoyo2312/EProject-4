package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class VideoNotFoundException extends ResourceNotFoundException {

    public VideoNotFoundException(String videoId) {
        super("VIDEO_NOT_FOUND", "Video not found: " + videoId);
    }
}
