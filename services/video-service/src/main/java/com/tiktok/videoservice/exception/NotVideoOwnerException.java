package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotVideoOwnerException extends ForbiddenException {

    public NotVideoOwnerException(String videoId) {
        super("NOT_VIDEO_OWNER", "You do not own video: " + videoId);
    }
}
