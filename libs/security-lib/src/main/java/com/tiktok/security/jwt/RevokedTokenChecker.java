package com.tiktok.security.jwt;

/**
 * Answers whether an access token id (jti) was revoked before its natural expiry — i.e. the
 * user logged out. Kept as an interface so {@link JwtAuthenticationFilter} carries no Redis
 * types: services without Redis on the classpath get the no-op implementation and fall back to
 * signature/expiry validation alone (api-gateway still enforces revocation at the edge).
 */
@FunctionalInterface
public interface RevokedTokenChecker {

    boolean isRevoked(String jti);
}
