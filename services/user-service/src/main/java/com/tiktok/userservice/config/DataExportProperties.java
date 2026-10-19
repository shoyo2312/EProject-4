package com.tiktok.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** "Download your data": how the archive is built, kept and handed out. */
@ConfigurationProperties(prefix = "user.data-export")
public record DataExportProperties(

        /** How long a worker may hold a request before another may take it over. */
        Duration lease,

        /** Builds a request may take before it is marked FAILED. */
        int maxAttempts,

        /** How long a finished archive can be downloaded — four days, as live. */
        Duration availableFor,

        /** How long one download link works. Short: a new one is minted on every click. */
        Duration linkTtl,

        /** Rows the cleanup looks at per run. */
        int cleanupBatchSize
) {
}
