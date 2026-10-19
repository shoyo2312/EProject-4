package com.tiktok.moderation.rules;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.text.NormalizedText;
import com.tiktok.moderation.text.TextNormalizer;
import org.springframework.core.io.Resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Terms that flag a text, read from a file of {@code term | CATEGORY | severity} lines — or built
 * from a list: admin terms, a creator's keywords.
 *
 * <p><b>How a term is written decides how it matches.</b> A term written with Vietnamese diacritics
 * (or {@code đ}) is matched against the text with its diacritics; a term written in plain ASCII is
 * matched against the text with diacritics removed. Folding everything would be the obvious design
 * and it is wrong for Vietnamese, because the folded forms of its worst words are among its most
 * common ones: {@code cặc} folds to {@code cac}, which is {@code các} (the plural marker);
 * {@code lồn} to {@code lon}, which is {@code lớn} ("big"); {@code buồi} to {@code buoi}, which is
 * {@code buổi} ("session"); {@code đĩ} to {@code di}, which is {@code đi} ("go"). Written with their
 * diacritics they catch exactly themselves. An ASCII term catches every spelling of itself —
 * {@code dm} matches {@code đm}, {@code ĐM}, {@code đ.m} — and is kept for forms with no innocent
 * reading.
 *
 * <p>Matching is by whole syllables, one to {@link #longestTerm} in a row, each window one hash
 * lookup. Never by substring: {@code ngu} would otherwise match inside {@code nguyên}. A term of two
 * syllables or more also matches written as one word — {@code địtmẹ}, {@code #nhàcái} — which is
 * how a hashtag has to write it and how a comment dodging the list chooses to. Still a whole word:
 * {@code nhàcáiuytín} is not {@code nhà cái}.
 *
 * <p>Loading fails loudly — an unknown category, a severity other than 2 or 3, a missing version.
 * A list that half-loads is a filter that silently stopped filtering.
 */
public final class Blocklist {

    private static final String VERSION_PREFIX = "version:";

    /** One term, as written, with what matching it means. */
    public record Term(String term, Category category, int severity) {
    }

    /**
     * Where a term lives in the matcher: the syllables it normalizes to, and which of the two maps
     * holds it. Two spellings with the same key are the same term to the matcher.
     */
    private record Key(String text, boolean folded) {
    }

    private final String version;
    private final Map<String, Term> accentedTerms;
    private final Map<String, Term> foldedTerms;
    /** The terms of two syllables or more, keyed by their syllables written as one word. */
    private final Map<String, Term> accentedJoined;
    private final Map<String, Term> foldedJoined;
    private final int longestTerm;
    private final Flag.Source source;

    private Blocklist(String version, Map<String, Term> accentedTerms, Map<String, Term> foldedTerms,
                      Flag.Source source) {
        this.version = version;
        this.accentedTerms = Map.copyOf(accentedTerms);
        this.foldedTerms = Map.copyOf(foldedTerms);
        this.accentedJoined = joined(accentedTerms);
        this.foldedJoined = joined(foldedTerms);
        this.source = source;
        int longest = 0;
        for (String key : accentedTerms.keySet()) {
            longest = Math.max(longest, syllableCount(key));
        }
        for (String key : foldedTerms.keySet()) {
            longest = Math.max(longest, syllableCount(key));
        }
        this.longestTerm = longest;
    }

    public static Blocklist load(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return parse(reader.lines().toList(), resource.getDescription());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the text moderation blocklist " + resource, e);
        }
    }

    public static Blocklist parse(List<String> lines, String source) {
        String version = null;
        List<Term> terms = new ArrayList<>();

        for (int number = 1; number <= lines.size(); number++) {
            String line = lines.get(number - 1).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith(VERSION_PREFIX)) {
                version = line.substring(VERSION_PREFIX.length()).strip();
                continue;
            }
            Term term = term(line, number, source);
            if (keyOrNull(term.term()) == null) {
                throw invalid(source, number, "the term has no letters or digits");
            }
            terms.add(term);
        }

        if (version == null || version.isEmpty()) {
            throw new IllegalStateException("Blocklist " + source + " has no '" + VERSION_PREFIX + "' line; "
                    + "every verdict records it, so it is required");
        }
        return of(version, terms, Flag.Source.RULES);
    }

    /**
     * A list built in code. Each term must be severity 2 or 3 and hold a letter or a digit — see
     * {@link #isMatchable}; callers holding text from people check that first.
     *
     * @throws IllegalArgumentException for a term that breaks either rule
     */
    public static Blocklist of(String version, List<Term> terms, Flag.Source source) {
        Map<String, Term> accented = new HashMap<>();
        Map<String, Term> folded = new HashMap<>();
        for (Term term : terms) {
            if (term.severity() != Flag.HOLD && term.severity() != Flag.REJECT) {
                throw new IllegalArgumentException("Term '" + term.term() + "': severity must be "
                        + Flag.HOLD + " or " + Flag.REJECT);
            }
            Key key = keyOrNull(term.term());
            if (key == null) {
                throw new IllegalArgumentException("Term '" + term.term() + "' has no letters or digits");
            }
            (key.folded() ? folded : accented).merge(key.text(), term, Blocklist::moreSevere);
        }
        return new Blocklist(version, accented, folded, source);
    }

    /**
     * This list with {@code other}'s terms added, under {@code version}. On a term both hold, the
     * more severe entry wins — an admin can make a shipped HOLD a REJECT, never quietly the reverse:
     * taking a term out is {@link #without}, which says so.
     */
    public Blocklist merge(Blocklist other, String version) {
        Map<String, Term> accented = new HashMap<>(accentedTerms);
        Map<String, Term> folded = new HashMap<>(foldedTerms);
        other.accentedTerms.forEach((key, term) -> accented.merge(key, term, Blocklist::moreSevere));
        other.foldedTerms.forEach((key, term) -> folded.merge(key, term, Blocklist::moreSevere));
        return new Blocklist(version, accented, folded, source);
    }

    /** This list less every term that keys the same as one of {@code terms}, under {@code version}. */
    public Blocklist without(Collection<String> terms, String version) {
        Map<String, Term> accented = new HashMap<>(accentedTerms);
        Map<String, Term> folded = new HashMap<>(foldedTerms);
        for (String term : terms) {
            Key key = keyOrNull(term);
            if (key != null) {
                (key.folded() ? folded : accented).remove(key.text());
            }
        }
        return new Blocklist(version, accented, folded, source);
    }

    /** Whether a term keying the same as {@code term} is on this list. */
    public boolean contains(String term) {
        return find(term).isPresent();
    }

    /** The entry a term keying the same as {@code term} has on this list, as it was written there. */
    public Optional<Term> find(String term) {
        Key key = keyOrNull(term);
        return key == null
                ? Optional.empty()
                : Optional.ofNullable((key.folded() ? foldedTerms : accentedTerms).get(key.text()));
    }

    /**
     * Whether {@code term} can be on a list at all: something left once it is normalized. A term of
     * only punctuation or emoji keys to nothing and would never match.
     */
    public static boolean isMatchable(String term) {
        return keyOrNull(term) != null;
    }

    /**
     * The term as the matcher keys it — its syllables, one space apart, diacritics kept when it was
     * written with them. Two spellings with the same key are one term: "Nhà  Cái" and "nhà cái"
     * share one, "nha cai" has its own (it matches every spelling, the other only the accented one).
     *
     * @throws IllegalArgumentException when the term has no letters or digits
     */
    public static String keyOf(String term) {
        Key key = keyOrNull(term);
        if (key == null) {
            throw new IllegalArgumentException("Term '" + term + "' has no letters or digits");
        }
        return key.text();
    }

    private static Key keyOrNull(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        NormalizedText normalized = TextNormalizer.normalize(term);
        List<String> syllables = normalized.accented().getFirst();
        if (syllables.isEmpty()) {
            return null;
        }
        String key = String.join(" ", syllables);
        // A term that folding changes was written with diacritics, and only matches with them.
        return new Key(key, key.equals(normalized.foldedText()));
    }

    /**
     * Each term of two syllables or more under the key its syllables get when written as one word —
     * normalized again, since joined they read differently: {@code 3} next to letters is an
     * {@code e}, and a letter ending one syllable and starting the next can make a run to squeeze.
     */
    private static Map<String, Term> joined(Map<String, Term> terms) {
        Map<String, Term> joined = new HashMap<>();
        terms.forEach((key, term) -> {
            if (syllableCount(key) < 2) {
                return;
            }
            List<String> asOneWord = TextNormalizer.normalize(key.replace(" ", "")).accented().getFirst();
            if (asOneWord.size() == 1) {
                joined.merge(asOneWord.getFirst(), term, Blocklist::moreSevere);
            }
        });
        return Map.copyOf(joined);
    }

    private static Term moreSevere(Term existing, Term added) {
        return added.severity() > existing.severity() ? added : existing;
    }

    private static int syllableCount(String key) {
        return key.split(" ").length;
    }

    private static Term term(String line, int number, String source) {
        String[] parts = line.split("\\|");
        if (parts.length != 3) {
            throw invalid(source, number, "expected 'term | CATEGORY | severity'");
        }
        Category category;
        try {
            category = Category.valueOf(parts[1].strip());
        } catch (IllegalArgumentException e) {
            throw invalid(source, number, "unknown category '" + parts[1].strip() + "'");
        }
        int severity;
        try {
            severity = Integer.parseInt(parts[2].strip());
        } catch (NumberFormatException e) {
            severity = -1;
        }
        if (severity != Flag.HOLD && severity != Flag.REJECT) {
            throw invalid(source, number, "severity must be " + Flag.HOLD + " or " + Flag.REJECT);
        }
        return new Term(parts[0].strip(), category, severity);
    }

    private static IllegalStateException invalid(String source, int line, String why) {
        return new IllegalStateException("Blocklist " + source + " line " + line + ": " + why);
    }

    /** Every term found in the text, each once, whichever variant of the text it was found in. */
    public List<Flag> match(NormalizedText text) {
        Map<String, Flag> found = new LinkedHashMap<>();
        for (List<String> syllables : text.accented()) {
            scan(syllables, accentedTerms, accentedJoined, found);
        }
        for (List<String> syllables : text.folded()) {
            scan(syllables, foldedTerms, foldedJoined, found);
        }
        return List.copyOf(found.values());
    }

    private void scan(List<String> syllables, Map<String, Term> terms, Map<String, Term> joined,
                      Map<String, Flag> found) {
        for (int start = 0; start < syllables.size(); start++) {
            report(joined.get(syllables.get(start)), found);
            StringBuilder window = new StringBuilder();
            for (int length = 1; length <= longestTerm && start + length <= syllables.size(); length++) {
                if (length > 1) {
                    window.append(' ');
                }
                window.append(syllables.get(start + length - 1));
                report(terms.get(window.toString()), found);
            }
        }
    }

    private void report(Term term, Map<String, Flag> found) {
        if (term != null) {
            found.putIfAbsent(term.term(), new Flag(term.category(), term.severity(), "term:" + term.term(), source));
        }
    }

    /** The list's own {@code version:} line, or what it was built under. */
    public String version() {
        return version;
    }

    public int size() {
        return accentedTerms.size() + foldedTerms.size();
    }
}
