package com.tiktok.interactionservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How many comments one account may post, and how often the same text. A tier set to zero is off —
 * the integration tests switch them off to post in a loop, and an operator can do the same for one
 * tier without a deploy.
 *
 * @param rateLimit every tier is checked on every post; the first one full refuses it
 * @param duplicate the same text, matched loosely (see {@code TextNormalizer.fingerprint})
 */
@ConfigurationProperties(prefix = "interaction.comment")
public record CommentLimitProperties(
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Duplicate duplicate
) {

    /**
     * @param cooldown        the gap required between two comments — what stops a bot firing in a loop
     * @param perMinute       comments per minute
     * @param perHour         comments per hour across every video — the tier a bot spreading one
     *                        comment over many videos meets; the per-video tier alone never stopped it
     * @param perDay          comments per day
     * @param perVideoPerHour comments per hour on one video — flooding one thread
     */
    public record RateLimit(
            @DefaultValue("3s") Duration cooldown,
            @DefaultValue("10") int perMinute,
            @DefaultValue("100") int perHour,
            @DefaultValue("500") int perDay,
            @DefaultValue("60") int perVideoPerHour
    ) {
    }

    /**
     * @param window    how long a text counts as recently posted
     * @param maxCopies how many times the same text may be posted within the window, each in a
     *                  different place — a video's thread, or a different comment replied to; in
     *                  one place, once is the limit
     */
    public record Duplicate(
            @DefaultValue("10m") Duration window,
            @DefaultValue("5") int maxCopies
    ) {
    }
}
