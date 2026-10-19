package com.tiktok.moderation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What screening found in one text.
 *
 * @param decision what the flags say on their own: REJECT when any is severity 3, HOLD when any is
 *                 severity 2, ALLOW otherwise — notes decide nothing. Whether anyone acts on it is
 *                 the caller's call: each flag's {@link Flag.Source} has its own switch, and a video
 *                 owner's settings can waive some categories on their own videos
 * @param flags    everything that fired, most severe first
 * @param version  which rules decided — {@code rules-<blocklist version>}, then
 *                 {@code +admin-<revision>} once admin terms are in, then {@code +<model version>}
 *                 when the classifier was asked. Stored with every flagged comment and every screened
 *                 video, because a decision that cannot be attributed to what made it cannot be used
 *                 to tune it
 */
public record TextVerdict(Decision decision, List<Flag> flags, String version) {

    private static final Comparator<Flag> MOST_SEVERE_FIRST =
            Comparator.comparingInt(Flag::severity).reversed();

    public TextVerdict {
        flags = flags.stream().sorted(MOST_SEVERE_FIRST).toList();
    }

    public static TextVerdict of(List<Flag> flags, String version) {
        Decision decision = Decision.ALLOW;
        for (Flag flag : flags) {
            if (flag.severity() >= Flag.REJECT) {
                decision = Decision.REJECT;
                break;
            }
            if (flag.severity() >= Flag.HOLD) {
                decision = Decision.HOLD;
            }
        }
        return new TextVerdict(decision, flags, version);
    }

    public static TextVerdict clean(String version) {
        return new TextVerdict(Decision.ALLOW, List.of(), version);
    }

    /** This verdict with more flags, decided again, under {@code version}. */
    public TextVerdict with(List<Flag> more, String version) {
        if (more.isEmpty() && version.equals(this.version)) {
            return this;
        }
        List<Flag> all = new ArrayList<>(flags);
        all.addAll(more);
        return of(all, version);
    }

    /** The flag that decided — the most severe one — if anything fired. */
    public Optional<Flag> worst() {
        return flags.stream().findFirst();
    }

    /** Every flag on one line, most severe first: what a flagged comment stores as its reason. */
    public String summary() {
        return flags.stream().map(Flag::toString).collect(Collectors.joining("; "));
    }
}
