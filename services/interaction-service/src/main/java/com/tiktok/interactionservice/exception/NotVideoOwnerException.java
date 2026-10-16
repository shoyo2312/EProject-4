package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * Only a video's owner reviews the comments held on it. Also what an owner gets while video-service
 * cannot confirm who owns the video: the queue holds text nobody has cleared, so not knowing is
 * treated as no.
 */
public class NotVideoOwnerException extends ForbiddenException {

    public NotVideoOwnerException(Long videoId) {
        super("NOT_VIDEO_OWNER", "You do not own video: " + videoId);
    }
}
