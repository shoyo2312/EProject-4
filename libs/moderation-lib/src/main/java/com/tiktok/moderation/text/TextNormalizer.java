package com.tiktok.moderation.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The matching form of a text: lossy on purpose, and only ever compared, never stored or shown.
 *
 * <p>Each step undoes one way of writing a word so that a reader still recognises it and a string
 * comparison does not:
 *
 * <ol>
 *   <li>NFKC — full-width letters, mathematical alphanumerics (𝐝𝐦) and ligatures become plain
 *       letters — then lower case, any combining mark NFKC left loose dropped, then look-alikes
 *       read as the letter they pass for: Cyrillic and Greek twins of Latin letters, and
 *       {@code ð} for {@code đ}.
 *   <li>Split into syllables on anything that is not a letter or a digit.
 *   <li>Inside a syllable that has letters, digits and a few symbols read as the letters they stand
 *       in for ({@code d1t}, {@code c@c}). A syllable of digits only is a number and stays one.
 *   <li>A letter repeated three times or more collapses to one ({@code nguuuu}); doubles stay, since
 *       they are spelling ({@code xoong}, {@code good}).
 *   <li>Diacritics removed, {@code đ} read as {@code d} — kept as a second variant, never instead
 *       of the first: see {@code Blocklist} for why Vietnamese cannot be matched folded alone.
 *   <li>A run of one-letter syllables also read joined, which is how a word is spelled out to get
 *       past a filter: {@code đ.m}, {@code v c l}.
 * </ol>
 */
public final class TextNormalizer {

    /**
     * Letters drawn the same as another, used to dodge a word list: Cyrillic and Greek twins of Latin
     * letters, and the two capitals drawn exactly as {@code Đ} — Icelandic {@code Ð}, African
     * {@code Ɖ} — in the lower case they reach this map in, read as the {@code đ} they pass for.
     * Left as they are, neither is {@code đ} nor folds to {@code d}, so a word written with one
     * matched no form of any term.
     */
    private static final Map<Integer, Integer> LOOK_ALIKES = lookAlikes(
            "аa еe ёe оo рp сc уy хx кk мm іi јj ѕs ԁd ӏl һh ԛq ԝw "
                    + "αa οo εe ιi κk νv ρp τt υu χx γy "
                    + "ðđ ɖđ");

    /** Digits and symbols standing in for letters, inside a word. */
    private static final Map<Integer, Integer> LEET = lookAlikes("0o 1i 3e 4a 5s 7t @a $s !i");

    private static final int MIN_SQUEEZED_RUN = 3;

    private TextNormalizer() {
    }

    public static NormalizedText normalize(String text) {
        List<String> syllables = syllables(prepare(text == null ? "" : text));

        List<String> accented = new ArrayList<>(syllables.size());
        for (String syllable : syllables) {
            accented.add(squeeze(decodeLeet(syllable)));
        }
        List<String> folded = accented.stream().map(TextNormalizer::fold).toList();

        return new NormalizedText(syllables, withJoined(accented), withJoined(folded));
    }

    /**
     * What two texts share when one is a near copy of the other — "Hay quá!!!" and "hay qua" — for
     * spotting the same comment posted again. Only syllables count, so punctuation, case,
     * diacritics and spacing do not; a text with no syllables at all (emoji only) falls back to
     * its own characters, or every emoji-only comment would be a copy of every other.
     */
    public static String fingerprint(String text) {
        NormalizedText normalized = normalize(text);
        if (!normalized.isEmpty()) {
            return normalized.foldedText();
        }
        String prepared = prepare(text == null ? "" : text);
        StringBuilder out = new StringBuilder(prepared.length());
        prepared.codePoints().filter(cp -> !Character.isWhitespace(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** Diacritics removed and {@code đ} read as {@code d}; everything else, case included, as it was. */
    public static String fold(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(cp -> {
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                return;
            }
            out.appendCodePoint(cp == 'đ' ? 'd' : cp == 'Đ' ? 'D' : cp);
        });
        return out.toString();
    }

    /**
     * NFKC composes every Vietnamese letter, so a combining mark still loose after it is one no word
     * needs: a variation selector nobody sees ({@code lồn} + U+FE0E), an underline drawn under each
     * letter ({@code l̲ồ̲n̲}). Kept, it would make a listed word a different string; dropped, the word
     * is itself again.
     */
    private static String prepare(String text) {
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        lower.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                return;
            }
            out.appendCodePoint(LOOK_ALIKES.getOrDefault(cp, cp));
        });
        return out.toString();
    }

    /**
     * Letters, digits and marks make up a syllable; anything else ends one. {@code @ $ !} between
     * two letters stay inside it, to be decoded as letters — anywhere else they are punctuation, so
     * a mention ({@code @minh}) and an exclamation keep splitting words as they should.
     */
    private static List<String> syllables(String text) {
        List<String> syllables = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int[] codePoints = text.codePoints().toArray();

        for (int i = 0; i < codePoints.length; i++) {
            int cp = codePoints[i];
            if (isWordPart(cp)) {
                current.appendCodePoint(cp);
            } else if (isLeetSymbol(cp) && !current.isEmpty()
                    && Character.isLetter(current.codePointBefore(current.length()))
                    && i + 1 < codePoints.length && Character.isLetter(codePoints[i + 1])) {
                current.appendCodePoint(cp);
            } else if (!current.isEmpty()) {
                syllables.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            syllables.add(current.toString());
        }
        return syllables;
    }

    private static boolean isWordPart(int cp) {
        int type = Character.getType(cp);
        return Character.isLetterOrDigit(cp)
                || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isLeetSymbol(int cp) {
        return cp == '@' || cp == '$' || cp == '!';
    }

    private static String decodeLeet(String syllable) {
        if (syllable.codePoints().noneMatch(Character::isLetter)) {
            return syllable;
        }
        StringBuilder out = new StringBuilder(syllable.length());
        syllable.codePoints().forEach(cp -> out.appendCodePoint(LEET.getOrDefault(cp, cp)));
        return out.toString();
    }

    private static String squeeze(String syllable) {
        int[] codePoints = syllable.codePoints().toArray();
        StringBuilder out = new StringBuilder(syllable.length());
        int i = 0;
        while (i < codePoints.length) {
            int run = 1;
            while (i + run < codePoints.length && codePoints[i + run] == codePoints[i]) {
                run++;
            }
            int keep = run >= MIN_SQUEEZED_RUN ? 1 : run;
            for (int k = 0; k < keep; k++) {
                out.appendCodePoint(codePoints[i]);
            }
            i += run;
        }
        return out.toString();
    }

    /**
     * The sequence as written, plus — when it has two or more one-letter syllables in a row — the
     * same sequence with each such run joined into one syllable.
     */
    private static List<List<String>> withJoined(List<String> syllables) {
        List<String> joined = new ArrayList<>(syllables.size());
        StringBuilder run = new StringBuilder();
        int runLength = 0;
        boolean anyJoined = false;

        for (String syllable : syllables) {
            if (isSingleLetter(syllable)) {
                run.append(syllable);
                runLength++;
                continue;
            }
            anyJoined |= flush(joined, run, runLength);
            runLength = 0;
            joined.add(syllable);
        }
        anyJoined |= flush(joined, run, runLength);

        return anyJoined ? List.of(List.copyOf(syllables), List.copyOf(joined)) : List.of(List.copyOf(syllables));
    }

    private static boolean flush(List<String> joined, StringBuilder run, int runLength) {
        if (runLength == 0) {
            return false;
        }
        joined.add(run.toString());
        run.setLength(0);
        return runLength > 1;
    }

    private static boolean isSingleLetter(String syllable) {
        return syllable.codePointCount(0, syllable.length()) == 1 && Character.isLetter(syllable.codePointAt(0));
    }

    private static Map<Integer, Integer> lookAlikes(String pairs) {
        Map<Integer, Integer> map = new HashMap<>();
        for (String pair : pairs.split(" ")) {
            int[] codePoints = pair.codePoints().toArray();
            map.put(codePoints[0], codePoints[1]);
        }
        return Map.copyOf(map);
    }
}
