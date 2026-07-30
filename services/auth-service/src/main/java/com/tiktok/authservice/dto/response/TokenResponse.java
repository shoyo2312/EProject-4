package com.tiktok.authservice.dto.response;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresInMillis
) {
}
