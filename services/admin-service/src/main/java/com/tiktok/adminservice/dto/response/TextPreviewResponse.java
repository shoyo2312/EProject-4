package com.tiktok.adminservice.dto.response;

import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;

import java.util.List;

/**
 * What screening makes of a text, before any service's switches: whether a service then acts on a
 * HOLD or a REJECT is its own {@code moderation.text.enforce} (and, for the model's flags, its
 * {@code moderation.text.classifier.enforce}); a video owner's comment filters come on top.
 *
 * @param decision           what the flags say — notes (severity 1) decide nothing
 * @param flags              everything that fired, most severe first, with who raised it
 * @param version            the rules, admin revision and model that decided
 * @param classifierAnswered false when the classifier is switched off or could not be reached — the
 *                           decision is then the rules' alone, as it would be in the services
 */
public record TextPreviewResponse(
        Decision decision,
        List<Flag> flags,
        String version,
        boolean classifierAnswered
) {
}
