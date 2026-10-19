package com.tiktok.moderation;

import com.tiktok.moderation.rules.AdminTerm;
import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.rules.SpamSignals;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextModeratorTest {

    private static final Blocklist LIST = Blocklist.parse(List.of(
            "version: t1",
            "lồn | PROFANITY | 2",
            "địt mẹ mày | PROFANITY | 3"), "test");

    private final TextModerator moderator =
            new TextModerator(LIST, new SpamSignals(List.of("youtube.com"), 5), true);

    private SimpleMeterRegistry registry;

    @BeforeEach
    void registerMeters() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void removeMeters() {
        Metrics.removeRegistry(registry);
    }

    @Test
    void cleanText_isAllowed_andCarriesTheListVersion() {
        TextVerdict verdict = moderator.screen("Video hay quá, cảm ơn bạn!", Surface.COMMENT);

        assertThat(verdict.decision()).isEqualTo(Decision.ALLOW);
        assertThat(verdict.flags()).isEmpty();
        assertThat(verdict.version()).isEqualTo("rules-t1").isEqualTo(moderator.version());
    }

    @Test
    void blankText_isAllowed() {
        assertThat(moderator.screen(null, Surface.VIDEO_DESCRIPTION).decision()).isEqualTo(Decision.ALLOW);
        assertThat(moderator.screen("  ", Surface.VIDEO_DESCRIPTION).decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void severityTwo_holds() {
        TextVerdict verdict = moderator.screen("cái lồn gì vậy", Surface.COMMENT);

        assertThat(verdict.decision()).isEqualTo(Decision.HOLD);
        assertThat(verdict.summary()).isEqualTo("PROFANITY:term:lồn");
    }

    @Test
    void aSpamSignal_holds() {
        assertThat(moderator.screen("vào abc.xyz nhé", Surface.COMMENT).decision()).isEqualTo(Decision.HOLD);
    }

    @Test
    void severityThree_rejects_andComesFirstAmongTheFlags() {
        TextVerdict verdict = moderator.screen("lồn, địt mẹ mày, abc.xyz", Surface.COMMENT);

        assertThat(verdict.decision()).isEqualTo(Decision.REJECT);
        assertThat(verdict.worst()).get().extracting(Flag::severity).isEqualTo(Flag.REJECT);
        assertThat(verdict.flags()).hasSize(3);
    }

    @Test
    void everyVerdict_isCounted_bySurfaceDecisionAndCategory() {
        moderator.screen("cái lồn gì vậy", Surface.COMMENT);
        moderator.screen("hay", Surface.VIDEO_TITLE);

        assertThat(registry.get("text.moderation.decisions")
                .tags("surface", "comment", "decision", "hold", "category", "PROFANITY", "enforced", "true")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("text.moderation.decisions")
                .tags("surface", "video_title", "decision", "allow", "category", "NONE")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void notEnforcing_stillDecides_andSaysSo() {
        TextModerator logOnly = new TextModerator(LIST, new SpamSignals(List.of(), 5), false);

        assertThat(logOnly.enforcing()).isFalse();
        assertThat(logOnly.screen("địt mẹ mày", Surface.COMMENT).decision()).isEqualTo(Decision.REJECT);
        assertThat(registry.get("text.moderation.decisions").tags("enforced", "false").counter().count())
                .isEqualTo(1.0);
    }
    @Test
    void adminTerms_addToTheShippedList_andNameTheirRevisionInTheVersion() {
        moderator.applyAdminTerms(7, List.of(
                new AdminTerm("cút đi", Category.INSULT, AdminTerm.Action.HOLD),
                new AdminTerm("đồ mất dạy", Category.INSULT, AdminTerm.Action.REJECT)));

        assertThat(moderator.version()).isEqualTo("rules-t1+admin-7");
        assertThat(moderator.adminRevision()).hasValue(7);
        TextVerdict held = moderator.screen("cút đi cho nước nó trong", Surface.COMMENT);
        assertThat(held.decision()).isEqualTo(Decision.HOLD);
        assertThat(held.version()).isEqualTo("rules-t1+admin-7");
        assertThat(moderator.screen("đồ mất dạy", Surface.COMMENT).decision()).isEqualTo(Decision.REJECT);
        // The shipped terms are still there.
        assertThat(moderator.screen("cái lồn gì vậy", Surface.COMMENT).decision()).isEqualTo(Decision.HOLD);
    }

    @Test
    void anExemptTerm_takesTheShippedOneOut_untilTheNextSnapshotPutsItBack() {
        moderator.applyAdminTerms(8, List.of(new AdminTerm("lồn", null, AdminTerm.Action.EXEMPT)));
        assertThat(moderator.screen("cái lồn gì vậy", Surface.COMMENT).decision()).isEqualTo(Decision.ALLOW);

        // A snapshot is the whole list, not a change to it.
        moderator.applyAdminTerms(9, List.of());
        assertThat(moderator.screen("cái lồn gì vậy", Surface.COMMENT).decision()).isEqualTo(Decision.HOLD);
        assertThat(moderator.shippedList().contains("lồn")).isTrue();
    }

    /** admin-service validates on the way in; one bad row still must not keep the others out. */
    @Test
    void anAdminTermThatCannotMatch_orLacksACategory_isSkipped_notThrown() {
        moderator.applyAdminTerms(10, List.of(
                new AdminTerm("!!!", Category.SPAM, AdminTerm.Action.HOLD),
                new AdminTerm("không danh mục", null, AdminTerm.Action.HOLD),
                new AdminTerm("cút đi", Category.INSULT, AdminTerm.Action.HOLD)));

        assertThat(moderator.screen("không danh mục", Surface.COMMENT).decision()).isEqualTo(Decision.ALLOW);
        assertThat(moderator.screen("cút đi", Surface.COMMENT).decision()).isEqualTo(Decision.HOLD);
    }

    @Test
    void beforeAnyAdminTerms_theRevisionIsEmpty() {
        assertThat(moderator.adminRevision()).isEmpty();
    }

    @Test
    void aNote_isRecorded_butDecidesNothing() {
        TextVerdict clean = TextVerdict.clean("rules-t1");
        TextVerdict noted = clean.with(List.of(new Flag(Category.HATE, Flag.NOTE, "model:HATE:0.55",
                Flag.Source.MODEL)), "rules-t1+m1");

        assertThat(noted.decision()).isEqualTo(Decision.ALLOW);
        assertThat(noted.summary()).isEqualTo("HATE:model:HATE:0.55");
        assertThat(noted.version()).isEqualTo("rules-t1+m1");
        assertThat(clean.with(List.of(new Flag(Category.HATE, Flag.HOLD, "model:HATE:0.95", Flag.Source.MODEL)),
                "v").decision()).isEqualTo(Decision.HOLD);
    }
}
