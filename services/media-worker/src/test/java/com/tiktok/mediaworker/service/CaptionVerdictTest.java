package com.tiktok.mediaworker.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class CaptionVerdictTest {

    private static VideoModerationCompletedEvent frames(ModerationVerdict verdict, String reason) {
        return VideoModerationCompletedEvent.of("v1", verdict, "nsfw", 0.12, 0, 10, 40L, "model", "nsfw-v1", reason);
    }

    @Test
    void aHeldCaption_turnsApprovedFramesIntoReview_keepingTheScores() {
        VideoModerationCompletedEvent combined =
                CaptionVerdict.combine(frames(ModerationVerdict.APPROVED, null), ModerationVerdict.REVIEW);

        assertThat(combined.verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(combined.reason()).isEqualTo(CaptionVerdict.REASON);
        assertThat(combined.videoId()).isEqualTo("v1");
        assertThat(combined.maxScore()).isEqualTo(0.12);
        assertThat(combined.totalFrames()).isEqualTo(10);
        assertThat(combined.modelVersion()).isEqualTo("nsfw-v1");
    }

    /** With the classifier switched off the frames are approved by decree; the caption still counts. */
    @Test
    void aHeldCaption_keepsWhateverTheFramesSaidAboutThemselves() {
        VideoModerationCompletedEvent combined = CaptionVerdict.combine(
                frames(ModerationVerdict.APPROVED, "Automatic moderation is disabled"), ModerationVerdict.REVIEW);

        assertThat(combined.verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(combined.reason()).contains(CaptionVerdict.REASON).contains("Automatic moderation is disabled");
    }

    /** Text never removes a video — a REJECTED caption cannot arrive, and would only hold if it did. */
    @Test
    void aRejectedCaption_holdsRatherThanRemoves() {
        assertThat(CaptionVerdict.combine(frames(ModerationVerdict.APPROVED, null), ModerationVerdict.REJECTED).verdict())
                .isEqualTo(ModerationVerdict.REVIEW);
    }

    @ParameterizedTest
    @EnumSource(value = ModerationVerdict.class, names = {"REVIEW", "REJECTED"})
    void framesAlreadyHeldOrRemoved_stayWhatTheyWere(ModerationVerdict verdict) {
        VideoModerationCompletedEvent original = frames(verdict, null);

        assertThat(CaptionVerdict.combine(original, ModerationVerdict.REVIEW)).isSameAs(original);
    }

    @Test
    void aCleanOrMissingCaptionVerdict_changesNothing() {
        VideoModerationCompletedEvent original = frames(ModerationVerdict.APPROVED, null);

        assertThat(CaptionVerdict.combine(original, ModerationVerdict.APPROVED)).isSameAs(original);
        assertThat(CaptionVerdict.combine(original, null)).isSameAs(original);
    }
}
