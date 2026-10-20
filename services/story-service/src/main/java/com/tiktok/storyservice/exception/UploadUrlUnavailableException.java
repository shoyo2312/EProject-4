package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class UploadUrlUnavailableException extends DomainException {

    public UploadUrlUnavailableException(Throwable cause) {
        super("UPLOAD_URL_UNAVAILABLE", "Cannot issue an upload URL right now", HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
