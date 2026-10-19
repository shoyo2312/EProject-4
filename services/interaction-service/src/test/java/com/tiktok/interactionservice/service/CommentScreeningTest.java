package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.TextModerator;
import com.tiktok.moderation.TextVerdict;
import com.tiktok.moderation.classifier.TextClassifier;
import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.rules.SpamSignals;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Each step of the order in {@link CommentScreening}, with the classifier's answers given by hand. */
class CommentScreeningTest {

    private static final Long OWNER = 9L;
    private static final Long AUTHOR = 1L;

    private static final Blocklist LIST = Blocklist.parse(List.of(
            "version: t1",
            "địt mẹ mày | PROFANITY | 3",
            "đồ ngu     | INSULT    | 2",
            "3 que      | HATE      | 2"), "test");

    private static final Flag MODEL_HOLD = new Flag(Category.HATE, Flag.HOLD, "model:HATE:0.97", Flag.Source.MODEL);
    private static final Flag MODEL_NOTE = new Flag(Category.HATE, Flag.NOTE, "model:HATE:0.85", Flag.Source.MODEL);

    private final TextClassifier classifier = mock(TextClassifier.class);

    private CommentScreening screening(boolean rulesEnforcing) {
        return new CommentScreening(new TextModerator(LIST, new SpamSignals(List.of(), 5), rulesEnforcing), classifier);
    }

    private CommentScreening screening() {
        return screening(true);
    }

    private static CommentPolicy policy(boolean filterSpamOffensive, boolean filterAll, List<String> keywords) {
        return new CommentPolicy(OWNER, false, CommentAudience.EVERYONE, filterSpamOffensive, filterAll, keywords);
    }

    private static final CommentPolicy DEFAULTS = policy(true, false, List.of());

    /** The classifier answering with {@code flag} (or nothing), as the real one does: added to the rules' verdict. */
    private void classifierSays(Flag flag) {
        when(classifier.refine(anyString(), eq(Surface.COMMENT), any(TextVerdict.class))).thenAnswer(invocation -> {
            TextVerdict verdict = invocation.getArgument(2);
            return verdict.with(flag == null ? List.of() : List.of(flag), verdict.version() + "+hsd-v1");
        });
    }

    @Test
    void aCleanComment_isAllowed_afterTheClassifierHadItsSay() {
        classifierSays(null);

        CommentScreening.Outcome outcome = screening().screen("video hay quá", AUTHOR, DEFAULTS);

        assertThat(outcome.decision()).isEqualTo(Decision.ALLOW);
        assertThat(outcome.verdict().version()).isEqualTo("rules-t1+hsd-v1");
    }

    @Test
    void anEnforcedReject_endsIt_withoutAModelCall() {
        CommentScreening.Outcome outcome = screening().screen("địt mẹ mày", AUTHOR, DEFAULTS);

        assertThat(outcome.decision()).isEqualTo(Decision.REJECT);
        verify(classifier, never()).refine(anyString(), any(), any());
    }

    /** They approve held comments; holding theirs would only have them approve themselves. */
    @Test
    void theOwnersOwnComment_isNeverHeld_butKeepsItsFlags_andIsStillRefused() {
        CommentScreening.Outcome outcome = screening().screen("đồ ngu", OWNER, policy(true, true, List.of("ngu")));

        assertThat(outcome.decision()).isEqualTo(Decision.ALLOW);
        assertThat(outcome.verdict().summary()).isEqualTo("INSULT:term:đồ ngu");
        assertThat(screening().screen("địt mẹ mày", OWNER, DEFAULTS).decision()).isEqualTo(Decision.REJECT);
        verify(classifier, never()).refine(anyString(), any(), any());
    }

    @Test
    void holdEverything_holdsAComment_withNoModelCall() {
        CommentScreening.Outcome outcome = screening().screen("video hay quá", AUTHOR, policy(true, true, List.of()));

        assertThat(outcome.decision()).isEqualTo(Decision.HOLD);
        assertThat(outcome.verdict().summary()).isEqualTo("CREATOR_FILTER:all");
        verify(classifier, never()).refine(anyString(), any(), any());
    }

    @Test
    void aKeyword_holdsTheComment() {
        CommentScreening.Outcome outcome = screening().screen("SPOILER: cuối phim", AUTHOR,
                policy(true, false, List.of("spoiler", "giảm giá")));

        assertThat(outcome.decision()).isEqualTo(Decision.HOLD);
        assertThat(outcome.verdict().flags()).singleElement()
                .satisfies(flag -> {
                    assertThat(flag.source()).isEqualTo(Flag.Source.CREATOR);
                    assertThat(flag.rule()).isEqualTo("term:spoiler");
                });
    }

    @Test
    void theRulesHold_withNoModelCall() {
        assertThat(screening().screen("đồ ngu", AUTHOR, DEFAULTS).decision()).isEqualTo(Decision.HOLD);
        verify(classifier, never()).refine(anyString(), any(), any());
    }

    /** An owner may waive rudeness and links on their own videos — and the classifier then gets its turn. */
    @Test
    void waivingSpamAndOffence_letsProfanityInsultsAndLinksThrough() {
        classifierSays(null);

        CommentScreening.Outcome outcome = screening().screen("đồ ngu, xem abc.xyz", AUTHOR, policy(false, false, List.of()));

        assertThat(outcome.decision()).isEqualTo(Decision.ALLOW);
        assertThat(outcome.verdict().flags()).extracting(Flag::category)
                .containsExactlyInAnyOrder(Category.INSULT, Category.SPAM);
        verify(classifier).refine(anyString(), eq(Surface.COMMENT), any());
    }

    /** Hate protects the people reading the thread, not the owner's taste. */
    @Test
    void waivingSpamAndOffence_doesNotWaiveHate_fromTheRulesOrTheModel() {
        assertThat(screening().screen("bọn 3 que", AUTHOR, policy(false, false, List.of())).decision())
                .isEqualTo(Decision.HOLD);

        classifierSays(MODEL_HOLD);
        assertThat(screening().screen("bọn kia cút về quê", AUTHOR, policy(false, false, List.of())).decision())
                .isEqualTo(Decision.HOLD);
    }

    @Test
    void aModelHold_holds_andAModelNote_isKeptWithoutHolding() {
        classifierSays(MODEL_HOLD);
        CommentScreening.Outcome held = screening().screen("bọn kia cút về quê", AUTHOR, DEFAULTS);
        assertThat(held.decision()).isEqualTo(Decision.HOLD);

        classifierSays(MODEL_NOTE);
        CommentScreening.Outcome noted = screening().screen("ủa sao vậy trời", AUTHOR, DEFAULTS);
        assertThat(noted.decision()).isEqualTo(Decision.ALLOW);
        assertThat(noted.verdict().summary()).isEqualTo("HATE:model:HATE:0.85");
    }

    /**
     * The rules only measuring: their hold counts for nothing, so the classifier is asked — and the
     * owner's own filters still hold, because the owner chose them.
     */
    @Test
    void rulesThatOnlyMeasure_holdNothing_butTheOwnersFiltersStillDo() {
        classifierSays(null);

        assertThat(screening(false).screen("đồ ngu", AUTHOR, DEFAULTS).decision()).isEqualTo(Decision.ALLOW);
        assertThat(screening(false).screen("địt mẹ mày", AUTHOR, DEFAULTS).decision()).isEqualTo(Decision.ALLOW);
        assertThat(screening(false).screen("spoiler đây", AUTHOR, policy(true, false, List.of("spoiler"))).decision())
                .isEqualTo(Decision.HOLD);
    }
}
