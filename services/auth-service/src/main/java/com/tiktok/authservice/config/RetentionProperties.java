package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long auth-service keeps rows that have stopped being useful, and how aggressively the
 * cleanup is allowed to delete them.
 *
 * <p>Each grace is measured from the moment the row became dead weight, not from when it was
 * created: an expired refresh token is kept for {@code refreshTokenGrace} past its expiry, a
 * published outbox row for {@code publishedOutboxGrace} past its publication. Set a grace
 * generously — its only job is to leave an audit trail for anyone investigating something that
 * happened a few days ago — but it cannot be so long that the table stops shrinking.
 */
@ConfigurationProperties(prefix = "auth.retention")
public record RetentionProperties(

        /** Rows deleted per statement. Small enough that no single delete holds locks for long. */
        int batchSize,

        /**
         * Ceiling on batches per table per run, so one sweep cannot run for hours against a
         * table that has never been cleaned. Whatever is left over goes in the next run.
         */
        int maxBatchesPerRun,

        Duration refreshTokenGrace,

        Duration verificationTokenGrace,

        Duration publishedOutboxGrace
) {
}
