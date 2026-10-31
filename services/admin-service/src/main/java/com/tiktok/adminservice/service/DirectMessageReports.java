package com.tiktok.adminservice.service;

import java.util.Set;

/**
 * The reasons the direct-message report flow offers, spelled as the client shows them and as
 * {@code report_reason_weights} keys them (V5, V10): a label that drifts from those would rank at
 * the default weight and, for the sensitive ones, age out under the stale sweep.
 */
final class DirectMessageReports {

    static final Set<String> REASONS = Set.of(
            "Nudity and sexual content",
            "Sextortion and intimate image abuse",
            "Regulated goods and activities",
            "Hate and harassment",
            "Frauds and scams",
            "Minor safety",
            "Misinformation",
            "Violence, abuse, and criminal exploitation",
            "Suicide and self-harm",
            "Disordered eating and unhealthy body image",
            "Shocking and graphic content",
            "Dangerous activities and challenges",
            "Deceptive behavior and spam",
            "Sharing personal information",
            "Dangerous goods or services",
            "Intellectual property infringement",
            "Advertisement does not match the item",
            "Other");

    private DirectMessageReports() {
    }
}
