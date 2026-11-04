package com.tiktok.crypto.jwt;

import io.jsonwebtoken.Claims;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * One key per signed-out session (one device), holding a placeholder value. Kills every
     * access token the session was ever issued, which the jti key cannot: the session that is
     * signed out remotely is not the one making the request, so nobody holds its jti.
     */
    public static final String SESSION_PREFIX = "auth:blacklist:session:";

    /** The session (device) an access token belongs to; absent on tokens minted before sessions. */
    public static final String CLAIM_SESSION_ID = "sid";

    private static final String CLAIM_JTI = "jti";

    private RevocationKeys() {
    }

    public static String forJti(String jti) {
        return JTI_PREFIX + jti;
    }

    public static String forUser(Object userId) {
        return USER_PREFIX + userId;
    }

    public static String forSession(Object sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    /** The session id of this token, or {@code null} if it carries none. */
    public static String sessionIdOf(Claims claims) {
        Object sid = claims.get(CLAIM_SESSION_ID);
        return sid == null ? null : sid.toString();
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

    /**
     * Every key that can revoke this token — the user's cutoff first, then the jti and the session
     * when the token has them — for a read side to fetch in a single MGET. Asked one at a time,
     * the check cost every authenticated request sequential Redis round trips.
     */
    public static List<String> keysOf(Claims claims) {
        List<String> keys = new ArrayList<>(3);
        keys.add(forUser(claims.getSubject()));
        String jti = jtiOf(claims);
        if (jti != null) {
            keys.add(forJti(jti));
        }
        String sid = sessionIdOf(claims);
        if (sid != null) {
            keys.add(forSession(sid));
        }
        return keys;
    }

    /**
     * The first value is the user's cutoff and is compared; every key after it is a plain marker,
     * so any value at all means revoked.
     *
     * @param values what an MGET of {@link #keysOf} answered, in that order
     */
    public static boolean isRevoked(Claims claims, List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        if (isIssuedBefore(claims, values.get(0))) {
            return true;
        }
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) != null) {
                return true;
            }
        }
        return false;
    }
}
