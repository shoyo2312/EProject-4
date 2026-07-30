package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotStoryOwnerException extends ForbiddenException {

    public NotStoryOwnerException(String storyId) {
        super("NOT_STORY_OWNER", "You do not own story: " + storyId);
    }
}
