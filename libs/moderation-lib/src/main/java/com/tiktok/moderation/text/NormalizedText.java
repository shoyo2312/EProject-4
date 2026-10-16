package com.tiktok.moderation.text;

import java.util.List;

/**
 * A text as the rules read it — see {@link TextNormalizer}. Never stored, never shown.
 *
 * @param syllables the text split into syllables, lower case, look-alikes mapped, nothing else
 *                  changed: what the betting-brand pattern reads, since "789bet" must keep its digits
 * @param accented  the syllables with leetspeak decoded and long repeats squeezed, diacritics kept —
 *                  first as written, then, when the text spells something out one letter at a
 *                  time, with those letters joined. What a blocklist term written with diacritics
 *                  is matched against
 * @param folded    the same sequences with diacritics removed and {@code đ} read as {@code d}. What a
 *                  term written in plain ASCII is matched against
 */
public record NormalizedText(List<String> syllables, List<List<String>> accented, List<List<String>> folded) {

    public boolean isEmpty() {
        return syllables.isEmpty();
    }

    /** The folded syllables as written, one space apart. */
    public String foldedText() {
        return String.join(" ", folded.getFirst());
    }
}
