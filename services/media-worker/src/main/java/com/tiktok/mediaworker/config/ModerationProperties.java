package com.tiktok.mediaworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How automatic moderation is called. The thresholds are deliberately not here — they belong to
 * the model and live in moderation-service, so tuning them is a restart of that container rather
 * than a rebuild of this one.
 *
 * @param enabled            false skips the check and approves everything, for a local run with
 *                           no moderation container up. An explicit operator choice: the default
 *                           is on, and nothing turns it off on its own when the service is down
 * @param frameCount         how many frames to sample and score per video, spread evenly across
 *                           its whole length
 * @param timeoutMillis      per attempt. Generous, because this is not on a request path — it is
 *                           a batch of frames through a CPU classifier
 * @param attempts           how many times to try before giving up and sending the video to the
 *                           admin queue
 * @param retryBackoffMillis pause between attempts
 */
@ConfigurationProperties(prefix = "media.moderation")
public record ModerationProperties(
        boolean enabled,
        String baseUrl,
        int frameCount,
        long timeoutMillis,
        int attempts,
        long retryBackoffMillis
) {
}
