package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class VideoNotAvailableException extends DomainException {
    public VideoNotAvailableException() {
        super("VIDEO_NOT_AVAILABLE", "Video is not available", HttpStatus.NOT_FOUND);
    }
}
