package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class StoryCheckUnavailableException extends DomainException {
    public StoryCheckUnavailableException() {
        super("STORY_CHECK_UNAVAILABLE", "Story availability check is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
