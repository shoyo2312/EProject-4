package com.tiktok.common.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message, HttpStatus.CONFLICT);
    }
}
