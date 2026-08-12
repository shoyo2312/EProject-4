package com.tiktok.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long user-service keeps processed-event claims, and how aggressively the cleanup is allowed
 * to delete them.
 */
@ConfigurationProperties(prefix = "user.retention")
public record RetentionProperties(

        /** Rows deleted per statement. Small enough that no single delete holds locks for long. */
        int batchSize,

        /**
         * Ceiling on batches per run, so one sweep cannot run for hours against a table that has
         * never been cleaned. Whatever is left over goes in the next run.
         */
        int maxBatchesPerRun,

        /**
         * How long a claim outlives the event it recorded. This is the only value here with a
         * correctness floor rather than a taste-based one: the row is what stops a redelivered
         * event from being processed twice, so it must outlive Kafka's own retention. Delete it
         * while the broker can still replay that offset — a consumer group reset, a topic replay —
         * and the claim is gone, the event looks new, and the counter it increments moves twice.
         */
        Duration processedEventGrace
) {
}
