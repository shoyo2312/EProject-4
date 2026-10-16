package com.tiktok.moderation.rules;

import com.tiktok.moderation.Category;

/**
 * A term an admin added to, or took out of, the shipped list — from admin-service, without a
 * deploy. See {@code TextModerator#applyAdminTerms}.
 *
 * @param category what a match is; null for {@link Action#EXEMPT}, which flags nothing
 */
public record AdminTerm(String term, Category category, Action action) {

    public enum Action {
        /** A match waits for a person, like a severity-2 line in the shipped list. */
        HOLD,
        /** A match is refused, like a severity-3 line. */
        REJECT,
        /**
         * The shipped term that keys the same no longer flags anything — for a false positive found
         * in production, until the next release fixes the file.
         */
        EXEMPT
    }
}
