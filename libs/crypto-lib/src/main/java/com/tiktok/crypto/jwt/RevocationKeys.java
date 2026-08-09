package com.tiktok.crypto.jwt;

import io.jsonwebtoken.Claims;

/**
 * The Redis contract between auth-service, which writes revocations, and the three sides that read
 * them: auth-service's own filter, security-lib's RedisRevokedTokenChecker, and api-gateway's
 * JwtReactiveAuthenticationManager.
 *
 * <p>Each read side still issues its own Redis call — two are blocking, one is reactive — but the
 * key names and the cutoff comparison live here so a typo cannot leave one side accepting tokens
 * the others reject.
 */
public final class RevocationKeys {

    /** One key per logged-out access token, holding a placeholder value. */
    public static final String JTI_PREFIX = "auth:blacklist:";

    /** One key per user, holding an epoch-millis cutoff; every token issued before it is dead. */
    public static final String USER_PREFIX = "auth:blacklist:user:";

    private static final String CLAIM_JTI = "jti";

    private RevocationKeys() {
    }

    public static String forJti(String jti) {
        return JTI_PREFIX + jti;
    }

    public static String forUser(Object userId) {
        return USER_PREFIX + userId;
    }

    /** The jti of this token, or {@code null} if it carries none. */
    public static String jtiOf(Claims claims) {
        return claims.get(CLAIM_JTI, String.class);
    }

    /**
     * Whether this token predates the cutoff stored under {@link #forUser}. A {@code null} value
     * means no cutoff is set for that user, so nothing is revoked.
     */
    public static boolean isIssuedBefore(Claims claims, String cutoffValue) {
        return cutoffValue != null && JwtProvider.issuedAtMillis(claims) < Long.parseLong(cutoffValue);
    }
}
