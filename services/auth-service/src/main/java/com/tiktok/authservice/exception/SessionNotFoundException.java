package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

/** No live session with that id belongs to the caller — already ended, or someone else's. */
public class SessionNotFoundException extends ResourceNotFoundException {

    public SessionNotFoundException() {
        super("SESSION_NOT_FOUND", "Session not found or already signed out");
    }
}
