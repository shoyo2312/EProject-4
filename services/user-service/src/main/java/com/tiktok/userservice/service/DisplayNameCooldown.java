package com.tiktok.userservice.service;

import java.time.Duration;
import java.time.Instant;

/**
 * A display name can be changed once every 7 days. Public because {@code UserProfileMapper}
 * answers the same question on the owner's own profile, so a client can disable the field rather
 * than learn the rule from a 429.
 */
public final class DisplayNameCooldown {

    public static final Duration PERIOD = Duration.ofDays(7);

    private DisplayNameCooldown() {
    }

    /**
     * When the name may next change, or null when it may change now — including for a profile
     * whose name has never been changed.
     */
    public static Instant changeableAt(Instant lastChangedAt, Instant now) {
        if (lastChangedAt == null) {
            return null;
        }
        Instant next = lastChangedAt.plus(PERIOD);
        return next.isAfter(now) ? next : null;
    }
}
