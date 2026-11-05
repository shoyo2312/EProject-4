package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class StoryNotAvailableException extends DomainException {
    public StoryNotAvailableException() {
        super("STORY_NOT_AVAILABLE", "Story is not available", HttpStatus.NOT_FOUND);
    }
}
