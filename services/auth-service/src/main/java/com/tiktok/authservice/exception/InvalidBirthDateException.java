package com.tiktok.authservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class InvalidBirthDateException extends BadRequestException {

    public InvalidBirthDateException() {
        super("BIRTH_DATE_INVALID", "Birth date must be at least 13 years ago and not before 1900");
    }
}
