package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class VideoNotShareableToRecipientException extends DomainException {
    public VideoNotShareableToRecipientException() {
        super("VIDEO_NOT_SHAREABLE_TO_RECIPIENT", "Recipient cannot view this video", HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
