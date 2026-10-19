package com.tiktok.common.http;

import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The secret one of this platform's services shows another on an internal call, in the
 * {@code X-Internal-Token} header.
 *
 * <p>Internal endpoints — user-service's and interaction-service's {@code /internal/} prefixes,
 * video-service's {@code /policy} — answer without a user's token, and api-gateway refusing those
 * paths is no boundary on its own: every service listens on its own port, and whatever reaches
 * that port has skipped the gateway. On a laptop that is the whole LAN. This is what those
 * endpoints check instead. api-gateway sends it to auth-service only, where it vouches for the
 * {@code X-Client-Ip} beside it; on any other route it would let every user request through the
 * gateway pass as an internal caller.
 *
 * <p>Never put it on a client that calls a third party (OAuth providers, Turnstile, avatar hosts):
 * it is the key to every internal endpoint.
 *
 * <p>{@code INTERNAL_API_TOKEN} is sent and accepted. {@code INTERNAL_API_TOKEN_ROTATING}, when
 * set, is accepted but never sent, so a new value rolls out with no moment where one service sends
 * what another refuses: give every service the new value as ROTATING, then swap the two
 * everywhere, then drop ROTATING.
 */
public final class InternalApiToken {

    public static final String HEADER = "X-Internal-Token";

    /** Shared by local and test runs when INTERNAL_API_TOKEN is unset; refused everywhere else. */
    static final String LOCAL_DEFAULT = "local-internal-api-token-not-for-production";

    private static final int MIN_BYTES = 32;

    private final String value;
    private final byte[] current;
    private final byte[] rotating;

    private InternalApiToken(String value, String rotating) {
        this.value = value;
        this.current = value.getBytes(StandardCharsets.UTF_8);
        this.rotating = rotating == null ? null : rotating.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Fails fast outside the local and test profiles, like JWT_SECRET: a service that cannot
     * authenticate its internal calls would otherwise start and then fail every one of them.
     */
    public static InternalApiToken from(Environment environment) {
        String token = blankToNull(environment.getProperty("INTERNAL_API_TOKEN"));
        String rotating = blankToNull(environment.getProperty("INTERNAL_API_TOKEN_ROTATING"));
        if (environment.matchesProfiles("local", "test")) {
            return new InternalApiToken(token == null ? LOCAL_DEFAULT : token, rotating);
        }
        requireStrong("INTERNAL_API_TOKEN", token);
        if (rotating != null) {
            requireStrong("INTERNAL_API_TOKEN_ROTATING", rotating);
        }
        return new InternalApiToken(token, rotating);
    }

    /** What this service sends. */
    public String value() {
        return value;
    }

    /** Constant-time, so a caller cannot recover the token a byte at a time from response timing. */
    public boolean accepts(String presented) {
        if (presented == null) {
            return false;
        }
        byte[] bytes = presented.getBytes(StandardCharsets.UTF_8);
        boolean matches = MessageDigest.isEqual(bytes, current);
        return rotating == null ? matches : matches | MessageDigest.isEqual(bytes, rotating);
    }

    private static void requireStrong(String name, String token) {
        if (token == null) {
            throw new IllegalStateException(name + " is not set. Every service that calls or serves"
                    + " an internal endpoint needs it, with the same value everywhere.");
        }
        if (LOCAL_DEFAULT.equals(token)) {
            throw new IllegalStateException(name + " is set to the local default. Set a real secret.");
        }
        if (token.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
            throw new IllegalStateException(name + " must be at least " + MIN_BYTES + " bytes.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
