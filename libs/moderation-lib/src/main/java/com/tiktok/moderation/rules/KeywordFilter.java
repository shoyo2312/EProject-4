package com.tiktok.moderation.rules;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.text.TextNormalizer;

import java.util.List;

/**
 * A video owner's own comment keywords, matched the way the blocklist matches: whole syllables, and
 * a keyword written with diacritics matches only the same diacritics. A creator filtering "tạch"
 * must not hold every "tách", for the same reason the shipped list keeps "cặc" away from "các".
 */
public final class KeywordFilter {

    private KeywordFilter() {
    }

    /**
     * One {@code CREATOR_FILTER} hold per keyword found, raised by the {@link Flag.Source#CREATOR}.
     * A keyword that cannot match anything (no letters or digits) is skipped rather than refused:
     * user-service validates what it stores, and a comment must not fail over an old row.
     */
    public static List<Flag> match(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<Blocklist.Term> terms = keywords.stream()
                .filter(Blocklist::isMatchable)
                .map(keyword -> new Blocklist.Term(keyword, Category.CREATOR_FILTER, Flag.HOLD))
                .toList();
        if (terms.isEmpty()) {
            return List.of();
        }
        return Blocklist.of("creator", terms, Flag.Source.CREATOR).match(TextNormalizer.normalize(text));
    }
}
