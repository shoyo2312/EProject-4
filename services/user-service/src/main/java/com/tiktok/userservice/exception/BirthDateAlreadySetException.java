package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class BirthDateAlreadySetException extends ConflictException {

    public BirthDateAlreadySetException() {
        super("BIRTH_DATE_ALREADY_SET", "The birth date has already been set and cannot be changed");
    }
}
