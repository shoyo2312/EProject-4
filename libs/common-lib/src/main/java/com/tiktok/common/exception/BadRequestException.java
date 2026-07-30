package com.tiktok.common.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends DomainException {

    public BadRequestException(String code, String message) {
        super(code, message, HttpStatus.BAD_REQUEST);
    }
}
