package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Signing an upload URL failed. Presigning is a local computation over the configured credentials,
 * so in practice this means the storage configuration is wrong rather than storage being down —
 * but either way it is not the caller's fault, and a bare 500 tells the client nothing about
 * whether retrying is worth it. The cause is logged, never returned: it can carry the endpoint and
 * bucket layout.
 */
public class UploadUrlUnavailableException extends DomainException {

    public UploadUrlUnavailableException(Throwable cause) {
        super("UPLOAD_URL_UNAVAILABLE", "Cannot issue an upload URL right now", HttpStatus.SERVICE_UNAVAILABLE);
        initCause(cause);
    }
}
