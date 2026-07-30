package com.tiktok.authservice.dto.response;

import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;

import java.time.Instant;

public record UserResponse(
        Long id,
        String username,
        String email,
        UserRole role,
        UserStatus status,
        Instant createdAt
) {
}
