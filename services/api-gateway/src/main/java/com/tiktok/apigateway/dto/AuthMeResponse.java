package com.tiktok.apigateway.dto;

import java.time.Instant;
import java.util.List;

/**
 * Mirrors auth-service's UserResponse fields. Kept local (not a shared lib type) since
 * api-gateway only needs it to deserialize GET /api/v1/auth/me for aggregation.
 *
 * @param providers null from an auth-service older than the field
 */
public record AuthMeResponse(
        Long id,
        String username,
        String email,
        String role,
        String status,
        boolean emailVerified,
        Instant createdAt,
        Instant usernameChangeableAt,
        boolean hasPassword,
        List<String> providers
) {
}
