package com.tiktok.security.jwt;

import io.jsonwebtoken.Claims;

/**
 * Answers whether an access token was revoked before its natural expiry — the user logged out
 * (single {@code jti}), or every session for that user was ended at once by a password reset or
 * a detected refresh-token replay (a user-wide cutoff compared against {@code iat}).
 *
 * <p>Takes the whole claim set rather than a jti because the user-wide cutoff needs the subject
 * and the issue time as well. Kept as an interface so {@link JwtAuthenticationFilter} carries no
 * Redis types: services without Redis on the classpath get the no-op implementation and fall back
 * to signature/expiry validation alone (api-gateway still enforces revocation at the edge).
 */
@FunctionalInterface
public interface RevokedTokenChecker {

    boolean isRevoked(Claims claims);
}
