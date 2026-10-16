package com.tiktok.interactionservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * When repeated violations stop an account from commenting, and for how long. See
 * {@code CommentStrikes}.
 *
 * @param threshold    strikes within {@code window} that restrict commenting; 0 switches strikes off
 * @param window       how long a strike counts, from the first one
 * @param restrictions how long each successive restriction lasts; past the last, the last repeats
 * @param stepWindow   how long without a restriction before the next one starts from the first
 *                     duration again
 */
@ConfigurationProperties(prefix = "interaction.comment.strikes")
public record CommentStrikeProperties(
        @DefaultValue("3") int threshold,
        @DefaultValue("24h") Duration window,
        @DefaultValue({"1h", "24h", "7d"}) List<Duration> restrictions,
        @DefaultValue("30d") Duration stepWindow
) {
}
