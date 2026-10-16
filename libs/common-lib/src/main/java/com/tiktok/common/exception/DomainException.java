package com.tiktok.common.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Base for all business exceptions. Each service's GlobalExceptionHandler catches this
 * and maps it to an ApiResponse using {@link #getCode()} and {@link #getStatus()}.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    protected DomainException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Headers the error response carries besides the body — {@code Retry-After} on a rate limit,
     * which a client can act on without parsing the message. Empty for almost every exception,
     * which is why it is a method to override rather than one more constructor argument.
     */
    public HttpHeaders getHeaders() {
        return new HttpHeaders();
    }
}
