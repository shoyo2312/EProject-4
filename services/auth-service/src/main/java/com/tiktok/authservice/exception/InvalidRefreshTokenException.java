package com.tiktok.authservice.exception;

import com.tiktok.common.exception.UnauthorizedException;

public class InvalidRefreshTokenException extends UnauthorizedException {

    public InvalidRefreshTokenException() {
        super("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }
}
