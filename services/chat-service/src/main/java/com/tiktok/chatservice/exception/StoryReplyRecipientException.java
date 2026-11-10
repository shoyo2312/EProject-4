package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class StoryReplyRecipientException extends DomainException {
    public StoryReplyRecipientException() {
        super("STORY_REPLY_RECIPIENT_MISMATCH", "A story reply goes to the story owner only", HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
