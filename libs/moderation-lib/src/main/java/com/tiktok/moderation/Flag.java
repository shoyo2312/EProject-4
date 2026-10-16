package com.tiktok.moderation;

/**
 * One reason a text was flagged.
 *
 * @param category what it is
 * @param severity 3 refuses the text, 2 sends it to a person, 1 is a note: recorded with the text and
 *                 deciding nothing — a classifier's opinion while it is only being measured, or one
 *                 below its threshold
 * @param rule     what fired, for whoever reviews it: {@code term:<the matched term>},
 *                 {@code link:<domain>}, {@code phone}, {@code email}, {@code betting:<syllable>},
 *                 {@code mentions:<count>}, {@code model:<label>:<score>}, {@code all}. Never a phone
 *                 number or an address — the flag is stored next to the text, and an admin screen is
 *                 not a place to copy contact details from
 * @param source   who raised it, which is what decides whether a caller acts on it — see {@link Source}
 */
public record Flag(Category category, int severity, String rule, Source source) {

    public static final int NOTE = 1;

    public static final int HOLD = 2;

    public static final int REJECT = 3;

    /** Who raised a flag. Each has its own switch, so a caller has to know which one to ask. */
    public enum Source {
        /** The shipped list, admin terms and the spam signals: acted on while the rules enforce. */
        RULES,
        /** The text classifier, whose severity already says whether it enforces. */
        MODEL,
        /** The video owner's own comment filters: always acted on — the owner chose them. */
        CREATOR
    }

    /** A flag from the rules, which is what every flag was before there was anything else. */
    public Flag(Category category, int severity, String rule) {
        this(category, severity, rule, Source.RULES);
    }

    @Override
    public String toString() {
        return category + ":" + rule;
    }
}
