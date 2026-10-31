package com.tiktok.storyservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.storyservice.exception.StoryCaptionRejectedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StoryCaptionModerationTest {

    private final TextModerator moderator = mock(TextModerator.class);
    private final TextClassifier classifier = mock(TextClassifier.class);
    private final StoryCaptionModeration moderation = new StoryCaptionModeration(moderator, classifier);

    private static TextVerdict verdict(int severity, Flag.Source source) {
        return TextVerdict.of(List.of(new Flag(Category.PROFANITY, severity, "r", source)), "v1");
    }

    @Test
    void blankCaptionIsNotScreened() {
        assertThat(moderation.screen(null)).isNull();
        assertThat(moderation.screen("  ")).isNull();
        verifyNoInteractions(moderator, classifier);
    }

    @Test
    void refusedTermRejectsWhileEnforcing() {
        when(moderator.enforcing()).thenReturn(true);
        when(moderator.screen("bad", Surface.STORY_CAPTION)).thenReturn(verdict(Flag.REJECT, Flag.Source.RULES));

        assertThatThrownBy(() -> moderation.screen("bad")).isInstanceOf(StoryCaptionRejectedException.class);
        verifyNoInteractions(classifier);
    }

    @Test
    void heldCaptionReviewsWhileEnforcing() {
        TextVerdict held = verdict(Flag.HOLD, Flag.Source.RULES);
        when(moderator.enforcing()).thenReturn(true);
        when(moderator.screen("meh", Surface.STORY_CAPTION)).thenReturn(held);
        when(classifier.refine(eq("meh"), eq(Surface.STORY_CAPTION), any())).thenReturn(held);

        assertThat(moderation.screen("meh")).isEqualTo(ModerationVerdict.REVIEW);
    }

    @Test
    void cleanCaptionApprovesWhileEnforcing() {
        TextVerdict clean = TextVerdict.clean("v1");
        when(moderator.enforcing()).thenReturn(true);
        when(moderator.screen("hi", Surface.STORY_CAPTION)).thenReturn(clean);
        when(classifier.refine(eq("hi"), eq(Surface.STORY_CAPTION), any())).thenReturn(clean);

        assertThat(moderation.screen("hi")).isEqualTo(ModerationVerdict.APPROVED);
    }

    @Test
    void measuredOnlyRulesHoldNothing() {
        TextVerdict rejected = verdict(Flag.REJECT, Flag.Source.RULES);
        when(moderator.enforcing()).thenReturn(false);
        when(moderator.screen("bad", Surface.STORY_CAPTION)).thenReturn(rejected);
        when(classifier.refine(eq("bad"), eq(Surface.STORY_CAPTION), any())).thenReturn(rejected);

        assertThat(moderation.screen("bad")).isNull();
    }

    @Test
    void classifierHoldIsEnforcedEvenWhileRulesOnlyMeasure() {
        TextVerdict rejected = verdict(Flag.REJECT, Flag.Source.RULES);
        when(moderator.enforcing()).thenReturn(false);
        when(moderator.screen("bad", Surface.STORY_CAPTION)).thenReturn(rejected);
        when(classifier.refine(eq("bad"), eq(Surface.STORY_CAPTION), any()))
                .thenReturn(TextVerdict.of(List.of(
                        new Flag(Category.PROFANITY, Flag.REJECT, "r", Flag.Source.RULES),
                        new Flag(Category.INSULT, Flag.HOLD, "m", Flag.Source.MODEL)), "v1"));

        assertThat(moderation.screen("bad")).isEqualTo(ModerationVerdict.REVIEW);
    }
}
