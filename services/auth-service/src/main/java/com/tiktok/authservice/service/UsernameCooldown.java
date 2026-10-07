package com.tiktok.authservice.service;

import java.time.Duration;
import java.time.Instant;

/**
 * A handle can be changed once every 30 days. Public because {@code UserMapper} answers the same
 * question for {@code GET /me}, so a client can disable the field rather than learn the rule from
 * a 429.
 */
public final class UsernameCooldown {

    public static final Duration PERIOD = Duration.ofDays(30);

    private UsernameCooldown() {
    }

    /**
     * When the handle may next change, or null when it may change now — including for an account
     * that has never changed it.
     */
    public static Instant changeableAt(Instant lastChangedAt, Instant now) {
        if (lastChangedAt == null) {
            return null;
        }
        Instant next = lastChangedAt.plus(PERIOD);
        return next.isAfter(now) ? next : null;
    }
}
