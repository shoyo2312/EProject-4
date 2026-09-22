package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param logoUrl absolute and publicly reachable — mail clients fetch it over HTTP and never see
 *                the app's own origin. Empty means the header falls back to the name alone, which
 *                is what happens in dev where nothing hosts the asset.
 */
@ConfigurationProperties(prefix = "auth.mail")
public record MailProperties(
        String from,
        String brandName,
        String logoUrl,
        String siteUrl
) {
}
