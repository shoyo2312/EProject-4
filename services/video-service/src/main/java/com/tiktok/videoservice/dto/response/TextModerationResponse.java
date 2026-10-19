package com.tiktok.videoservice.dto.response;

import com.tiktok.event.video.ModerationVerdict;

import java.time.Instant;
import java.util.List;

/**
 * What the text rules made of a video's caption, for the admin console only — the screen that has to
 * explain a PENDING_REVIEW video whose frames were fine. Kept off public reads for the reason
 * {@link ModerationResponse} is: an uploader who can see which word tripped the filter can write
 * around it.
 */
public record TextModerationResponse(
        ModerationVerdict verdict,
        List<String> flags,
        String version,
        /** False when the verdict was only recorded, not acted on. */
        boolean enforced,
        Instant checkedAt
) {
}
