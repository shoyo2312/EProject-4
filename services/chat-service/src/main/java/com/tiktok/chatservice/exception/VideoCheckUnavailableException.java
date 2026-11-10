package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class VideoCheckUnavailableException extends DomainException {
    public VideoCheckUnavailableException() {
        super("VIDEO_CHECK_UNAVAILABLE", "Video availability check is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
