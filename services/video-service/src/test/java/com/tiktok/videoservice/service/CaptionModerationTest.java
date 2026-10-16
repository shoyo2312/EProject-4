package com.tiktok.videoservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.rules.SpamSignals;
import com.tiktok.videoservice.entity.VideoTextModeration;
import com.tiktok.videoservice.exception.CaptionRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The decisions publish makes from the three verdicts — no Mongo, the shipped list. */
class CaptionModerationTest {

    private static final Blocklist LIST = Blocklist.load(new ClassPathResource("moderation/blocklist-vi.txt"));

    private static CaptionModeration moderation(boolean enforcing) {
        return moderation(enforcing, TextClassifier.disabled());
    }

    private static CaptionModeration moderation(boolean enforcing, TextClassifier classifier) {
        return new CaptionModeration(
                new TextModerator(LIST, new SpamSignals(List.of("youtube.com"), 5), enforcing), classifier);
    }

    /**
     * A classifier that adds {@code flag} to the description's verdict, as the real one does after a
     * call, and names its model in every verdict's version.
     */
    @SuppressWarnings("unchecked")
    private static TextClassifier classifierFlaggingTheDescription(Flag flag) {
        TextClassifier classifier = mock(TextClassifier.class);
        when(classifier.refine(anyList())).thenAnswer(invocation -> {
            List<TextClassifier.Input> inputs = invocation.getArgument(0);
            return java.util.stream.IntStream.range(0, inputs.size()).mapToObj(i -> {
                TextVerdict verdict = inputs.get(i).verdict();
                return verdict.with(i == 1 ? List.of(flag) : List.of(), verdict.version() + "+hsd-v1");
            }).toList();
        });
        return classifier;
    }

    @Test
    void theTitleIsJudgedFirst_whenSeveralFieldsAreRefused() {
        assertThatThrownBy(() -> moderation(true).screen("địt mẹ mày", "đồ con đĩ", List.of()))
                .extracting("code").isEqualTo("VIDEO_TITLE_REJECTED");
    }

    @Test
    void aCleanCaption_isApproved_withNoFlags() {
        VideoTextModeration result = moderation(true).screen("Nấu phở bò", "Công thức nhà mình", List.of("phở", "nauan"));

        assertThat(result.getVerdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(result.getFlags()).isEmpty();
        assertThat(CaptionModeration.eventVerdict(result)).isEqualTo(ModerationVerdict.APPROVED);
    }

    @Test
    void flagsName_theFieldTheyCameFrom() {
        VideoTextModeration result = moderation(true).screen("Vào fb88", null, List.of("casino"));

        assertThat(result.getVerdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(result.getFlags()).containsExactly("TITLE:SCAM:betting:fb88", "TAGS:SCAM:term:casino");
    }

    /**
     * Measuring only: nothing is refused, the would-be verdict is kept for counting, and the event
     * carries no verdict — one that was only measured must not hold the video in media-worker.
     */
    @Test
    void notEnforcing_refusesNothing_recordsTheVerdict_andAnnouncesNone() {
        VideoTextModeration result = moderation(false).screen("địt mẹ mày", null, List.of());

        assertThat(result.getVerdict()).isEqualTo(ModerationVerdict.REJECTED);
        assertThat(result.isEnforced()).isFalse();
        assertThat(CaptionModeration.eventVerdict(result)).isNull();
    }

    @Test
    void aClassifierHold_sendsTheVideoToReview_andIsRecordedWithItsField() {
        TextClassifier classifier = classifierFlaggingTheDescription(
                new Flag(Category.HATE, Flag.HOLD, "model:HATE:0.97", Flag.Source.MODEL));

        VideoTextModeration result = moderation(true, classifier).screen("Review phở", "bọn kia cút về quê", List.of());

        assertThat(result.getVerdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(result.getFlags()).containsExactly("DESCRIPTION:HATE:model:HATE:0.97");
        assertThat(result.getVersion()).endsWith("+hsd-v1");
        assertThat(CaptionModeration.eventVerdict(result)).isEqualTo(ModerationVerdict.REVIEW);
    }

    /** Only being measured: the opinion is on the record, the video is not held for it. */
    @Test
    void aClassifierNote_isRecorded_andHoldsNothing() {
        TextClassifier classifier = classifierFlaggingTheDescription(
                new Flag(Category.HATE, Flag.NOTE, "model:HATE:0.97", Flag.Source.MODEL));

        VideoTextModeration result = moderation(true, classifier).screen("Review phở", "bọn kia cút về quê", List.of());

        assertThat(result.getVerdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(result.getFlags()).containsExactly("DESCRIPTION:HATE:model:HATE:0.97");
    }

    /** Two switches: the classifier enforcing its own hold acts even while the rules only measure. */
    @Test
    void aClassifierHold_actsEvenWhileTheRulesOnlyMeasure() {
        TextClassifier classifier = classifierFlaggingTheDescription(
                new Flag(Category.HATE, Flag.HOLD, "model:HATE:0.97", Flag.Source.MODEL));

        VideoTextModeration result = moderation(false, classifier).screen("Review phở", "bọn kia cút về quê", List.of());

        assertThat(result.isEnforced()).isTrue();
        assertThat(CaptionModeration.eventVerdict(result)).isEqualTo(ModerationVerdict.REVIEW);
    }

    /**
     * The rules would refuse but only measure, the classifier holds: the record keeps what the rules
     * said, and the event carries the hold — never REJECTED, which it promises it never carries.
     */
    @Test
    void aMeasuredRefusal_thatTheClassifierHeld_isAnnouncedAsReview() {
        TextClassifier classifier = classifierFlaggingTheDescription(
                new Flag(Category.HATE, Flag.HOLD, "model:HATE:0.97", Flag.Source.MODEL));

        VideoTextModeration result = moderation(false, classifier).screen("địt mẹ mày", "bọn kia cút về quê", List.of());

        assertThat(result.getVerdict()).isEqualTo(ModerationVerdict.REJECTED);
        assertThat(result.isEnforced()).isTrue();
        assertThat(CaptionModeration.eventVerdict(result)).isEqualTo(ModerationVerdict.REVIEW);
    }

    /** A caption refused by the rules costs no model call. */
    @Test
    void aRefusedCaption_isNeverSentToTheClassifier() {
        TextClassifier classifier = mock(TextClassifier.class);

        assertThatThrownBy(() -> moderation(true, classifier).screen("địt mẹ mày", null, List.of()))
                .isInstanceOf(CaptionRejectedException.class);
        verifyNoInteractions(classifier);
    }

    @Test
    void aVideoWithNoRecord_announcesNoVerdict() {
        assertThat(CaptionModeration.eventVerdict(null)).isNull();
    }

    @Test
    void refusalsCarryTheFieldsOwnCode() {
        assertThat(new CaptionRejectedException(CaptionRejectedException.Field.TAGS).getCode())
                .isEqualTo("VIDEO_TAGS_REJECTED");
    }
}
