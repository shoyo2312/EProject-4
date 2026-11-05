package com.tiktok.storyservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.storyservice.exception.StoryCaptionRejectedException;
import com.tiktok.storyservice.exception.StoryOverlayRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Screens a story's caption, and the words laid over its picture, at create, as video-service's CaptionModeration does a video's. The
 * worst terms refuse the request — nothing is stored — while the rules enforce; a hold rides on
 * StoryCreatedEvent and media-worker folds it into the media verdict, so a held caption hides an
 * otherwise approved story. Text never rejects a story on its own.
 */
@Component
@RequiredArgsConstructor
public class StoryCaptionModeration {

    private final TextModerator textModerator;
    private final TextClassifier textClassifier;

    /** Without words over the picture. */
    public ModerationVerdict screen(String caption) {
        return screen(caption, List.of());
    }

    /**
     * @param overlayTexts the words laid over the picture in the editor, read as one text so a word
     *                     split across two stickers is still read whole; screened like the caption
     * @return the verdict to carry downstream, or null when there is nothing to enforce: no text,
     *         or rules that are only being measured and a classifier that held none of it
     * @throws StoryCaptionRejectedException for a refused term in the caption while the rules enforce
     * @throws StoryOverlayRejectedException for one in the words over the picture
     */
    public ModerationVerdict screen(String caption, List<String> overlayTexts) {
        String overlayText = overlayTexts == null ? "" : String.join(" ", overlayTexts).strip();
        if ((caption == null || caption.isBlank()) && overlayText.isEmpty()) {
            return null;
        }
        boolean enforcing = textModerator.enforcing();
        ModerationVerdict captionVerdict = caption == null || caption.isBlank() ? null
                : screenOne(caption, Surface.STORY_CAPTION, enforcing, StoryCaptionRejectedException::new);
        ModerationVerdict overlayVerdict = overlayText.isEmpty() ? null
                : screenOne(overlayText, Surface.STORY_OVERLAY, enforcing, StoryOverlayRejectedException::new);
        if (captionVerdict == ModerationVerdict.REVIEW || overlayVerdict == ModerationVerdict.REVIEW) {
            return ModerationVerdict.REVIEW;
        }
        return captionVerdict != null ? captionVerdict : overlayVerdict;
    }

    private ModerationVerdict screenOne(String text, Surface surface, boolean enforcing,
                                        Supplier<? extends RuntimeException> refusal) {
        TextVerdict rules = textModerator.screen(text, surface);
        if (enforcing && rules.decision() == Decision.REJECT) {
            throw refusal.get();
        }

        TextVerdict verdict = textClassifier.refine(text, surface, rules);
        boolean modelHeld = verdict.flags().stream()
                .anyMatch(flag -> flag.source() == Flag.Source.MODEL && flag.severity() >= Flag.HOLD);
        if (!enforcing && !modelHeld) {
            return null;
        }
        return verdict.decision() == Decision.ALLOW ? ModerationVerdict.APPROVED : ModerationVerdict.REVIEW;
    }
}
