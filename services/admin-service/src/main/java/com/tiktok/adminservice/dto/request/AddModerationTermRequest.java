package com.tiktok.adminservice.dto.request;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.rules.AdminTerm;
import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A term for the moderation list. Sanitized like any text a viewer types — a term with a zero-width
 * space in it would never match the comments it was added for.
 *
 * @param term     how it is written decides how it matches: with diacritics, only the same
 *                 diacritics; in plain ASCII, every spelling (see docs/moderation.md)
 * @param category required for HOLD and REJECT, ignored for EXEMPT
 * @param action   HOLD, REJECT, or EXEMPT — which takes a shipped term out
 * @param note     why, for the next admin reading the list
 */
public record AddModerationTermRequest(
        @NotBlank @MaxCodePoints(50) String term,
        Category category,
        @NotNull AdminTerm.Action action,
        @MaxCodePoints(500) String note
) {

    public AddModerationTermRequest {
        term = TextSanitizer.sanitizeLine(term);
        note = TextSanitizer.sanitize(note);
    }
}
