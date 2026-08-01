package com.tiktok.apigateway.dto;

import java.time.Instant;

/**
 * Mirrors auth-service's UserResponse fields. Kept local (not a shared lib type) since
 * api-gateway only needs it to deserialize GET /api/v1/auth/me for aggregation.
 */
public record AuthMeResponse(
        Long id,
        String username,
        String email,
        String role,
        String status,
        Instant createdAt
) {
}
