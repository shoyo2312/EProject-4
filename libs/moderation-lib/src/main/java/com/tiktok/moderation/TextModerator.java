package com.tiktok.moderation;

import com.tiktok.moderation.rules.AdminTerm;
import com.tiktok.moderation.rules.Blocklist;
import com.tiktok.moderation.rules.SpamSignals;
import com.tiktok.moderation.text.NormalizedText;
import com.tiktok.moderation.text.TextNormalizer;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Reads a text and says what should happen to it: ALLOW, HOLD for a person, or REJECT.
 *
 * <p>Rules only — the blocklist and the spam signals, in process, in microseconds. It answers
 * without any dependency being up, which is why a comment can wait for it. The classifier
 * ({@link com.tiktok.moderation.classifier.TextClassifier}) goes behind this, not instead of it.
 *
 * <p>The blocklist is the shipped file plus whatever admins have added or exempted since
 * ({@link #applyAdminTerms}), swapped in whole: a screening in progress keeps the list it started
 * with.
 *
 * <p>What a caller does with the answer depends on {@link #enforcing()}: switched off, the verdict
 * is still computed, logged and counted, and the caller lets the text through. Every verdict is
 * counted as {@code text_moderation_decisions_total}.
 */
public class TextModerator {

    private static final Logger log = LoggerFactory.getLogger(TextModerator.class);

    /** The list in force and the version string every verdict made with it carries. */
    private record Rules(Blocklist blocklist, String version, OptionalLong adminRevision) {
    }

    private final Blocklist shipped;
    private final SpamSignals spamSignals;
    private final boolean enforcing;
    private volatile Rules rules;

    public TextModerator(Blocklist blocklist, SpamSignals spamSignals, boolean enforcing) {
        this.shipped = blocklist;
        this.spamSignals = spamSignals;
        this.enforcing = enforcing;
        this.rules = new Rules(blocklist, "rules-" + blocklist.version(), OptionalLong.empty());
    }

    public TextVerdict screen(String text, Surface surface) {
        Rules current = rules;
        TextVerdict verdict = evaluate(text, surface, current);
        if (verdict.decision() != Decision.ALLOW) {
            // The flags, not the text: the matched term is enough to see what fires, and a log
            // line per comment body is a second copy of every comment in a place nobody moderates.
            log.info("Text moderation: {} {} ({}) — {}", surface.tag(), verdict.decision(),
                    enforcing ? "enforced" : "not enforced", verdict.summary());
        }
        Metrics.counter("text.moderation.decisions",
                "surface", surface.tag(),
                "decision", verdict.decision().name().toLowerCase(Locale.ROOT),
                "category", verdict.worst().map(flag -> flag.category().name()).orElse("NONE"),
                "enforced", String.valueOf(enforcing)).increment();
        return verdict;
    }

    private TextVerdict evaluate(String text, Surface surface, Rules current) {
        if (text == null || text.isBlank()) {
            return TextVerdict.clean(current.version());
        }
        NormalizedText normalized = TextNormalizer.normalize(text);
        List<Flag> flags = new ArrayList<>(current.blocklist().match(normalized));
        flags.addAll(spamSignals.detect(text, normalized, surface));
        return TextVerdict.of(flags, current.version());
    }

    /**
     * Rebuilds the list from the shipped file and admin-service's terms at {@code revision}: the
     * shipped terms less every EXEMPT one, plus the HOLD and REJECT ones (the more severe entry wins
     * where both name a term). Replaces whatever admin terms were in before — the snapshot is the
     * whole list, not a change to it.
     *
     * <p>A term that cannot match (no letters or digits) or a HOLD/REJECT without a category is
     * skipped and logged, not thrown: admin-service validates on the way in, and one bad row must
     * not keep every other term out.
     */
    public void applyAdminTerms(long revision, List<AdminTerm> terms) {
        String version = "rules-" + shipped.version() + "+admin-" + revision;
        List<String> exempt = new ArrayList<>();
        List<Blocklist.Term> added = new ArrayList<>();
        for (AdminTerm term : terms) {
            if (term == null || term.action() == null || !Blocklist.isMatchable(term.term())) {
                log.warn("Admin term {} at revision {} cannot match anything; skipped", term, revision);
                continue;
            }
            switch (term.action()) {
                case EXEMPT -> exempt.add(term.term());
                case HOLD, REJECT -> {
                    if (term.category() == null) {
                        log.warn("Admin term '{}' at revision {} has no category; skipped", term.term(), revision);
                        continue;
                    }
                    added.add(new Blocklist.Term(term.term(), term.category(),
                            term.action() == AdminTerm.Action.REJECT ? Flag.REJECT : Flag.HOLD));
                }
            }
        }
        Blocklist effective = shipped.without(exempt, version)
                .merge(Blocklist.of(version, added, Flag.Source.RULES), version);
        rules = new Rules(effective, version, OptionalLong.of(revision));
        log.info("Text moderation list is now {}: {} terms ({} shipped, {} added, {} exempted)",
                version, effective.size(), shipped.size(), added.size(), exempt.size());
    }

    /** The admin-service revision in force, or empty while only the shipped list applies. */
    public OptionalLong adminRevision() {
        return rules.adminRevision();
    }

    /** The file this service shipped with, before any admin term — what an EXEMPT must name. */
    public Blocklist shippedList() {
        return shipped;
    }

    /** Whether a caller should act on a HOLD or a REJECT from the rules, or only let it be counted. */
    public boolean enforcing() {
        return enforcing;
    }

    /** {@code rules-<blocklist version>[+admin-<revision>]}, the string every verdict carries. */
    public String version() {
        return rules.version();
    }
}
