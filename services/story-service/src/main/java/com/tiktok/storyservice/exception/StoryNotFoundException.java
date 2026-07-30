package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class StoryNotFoundException extends ResourceNotFoundException {

    public StoryNotFoundException(String storyId) {
        super("STORY_NOT_FOUND", "Story not found: " + storyId);
    }
}
