package com.tiktok.storyservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.storyservice.exception.StoryCaptionRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Screens a story's caption at create, as video-service's CaptionModeration does a video's. The
 * worst terms refuse the request — nothing is stored — while the rules enforce; a hold rides on
 * StoryCreatedEvent and media-worker folds it into the media verdict, so a held caption hides an
 * otherwise approved story. Text never rejects a story on its own.
 */
@Component
@RequiredArgsConstructor
public class StoryCaptionModeration {

    private final TextModerator textModerator;
    private final TextClassifier textClassifier;

    /**
     * @return the verdict to carry downstream, or null when there is nothing to enforce: no caption,
     *         or rules that are only being measured and a classifier that did not hold it
     * @throws StoryCaptionRejectedException for a refused term while the rules enforce
     */
    public ModerationVerdict screen(String caption) {
        if (caption == null || caption.isBlank()) {
            return null;
        }
        boolean enforcing = textModerator.enforcing();
        TextVerdict rules = textModerator.screen(caption, Surface.STORY_CAPTION);
        if (enforcing && rules.decision() == Decision.REJECT) {
            throw new StoryCaptionRejectedException();
        }

        TextVerdict verdict = textClassifier.refine(caption, Surface.STORY_CAPTION, rules);
        boolean modelHeld = verdict.flags().stream()
                .anyMatch(flag -> flag.source() == Flag.Source.MODEL && flag.severity() >= Flag.HOLD);
        if (!enforcing && !modelHeld) {
            return null;
        }
        return verdict.decision() == Decision.ALLOW ? ModerationVerdict.APPROVED : ModerationVerdict.REVIEW;
    }
}
