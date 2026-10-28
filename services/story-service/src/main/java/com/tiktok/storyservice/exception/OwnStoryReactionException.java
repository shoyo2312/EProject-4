package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class OwnStoryReactionException extends BadRequestException {

    public OwnStoryReactionException() {
        super("STORY_OWN_REACTION", "You can't react to your own story");
    }
}
