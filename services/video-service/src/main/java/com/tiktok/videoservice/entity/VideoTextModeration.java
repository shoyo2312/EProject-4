package com.tiktok.videoservice.entity;

import com.tiktok.event.video.ModerationVerdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * What the text rules and the classifier made of a video's title, description and tags when it was
 * published. Written once, with the insert, and never changed — no endpoint edits a caption.
 *
 * <p>Kept on the document for the reason {@link VideoModeration} is: a video in PENDING_REVIEW whose
 * frames scored nothing is unexplainable without it, and a blocklist cannot be tuned without seeing
 * which terms sent which videos to review and what the admin then decided.
 *
 * <p>The verdict itself reaches the status through media-worker, not through here: it rides on
 * VideoPublishedEvent and is folded into the moderation verdict there, so search and recommendation
 * hold the video too. See {@code CaptionModeration}.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoTextModeration {

    /**
     * APPROVED or REVIEW. REJECTED only when the rules were not enforced — enforced, a caption that
     * bad is refused before the video exists.
     */
    private ModerationVerdict verdict;

    /**
     * What fired, field first: {@code TITLE:PROFANITY:term:...}, {@code DESCRIPTION:SPAM:link:bit.ly},
     * {@code DESCRIPTION:HATE:model:HATE:0.97}. A model flag is also there when it only noted something
     * — that note is what its threshold gets tuned on.
     */
    private List<String> flags;

    /** What decided, e.g. {@code rules-2026.10.13+admin-4+hsd-v1} — the model only when it answered. */
    private String version;

    /**
     * False when text moderation was switched to measuring only and the classifier enforced no hold
     * of its own: the verdict was recorded and never acted on, and the video went through as if its
     * caption were clean.
     */
    private boolean enforced;

    private Instant checkedAt;
}
