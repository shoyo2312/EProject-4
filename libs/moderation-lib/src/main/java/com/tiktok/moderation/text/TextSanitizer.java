package com.tiktok.moderation.text;

import java.text.Normalizer;

/**
 * The stored form of a text a viewer will read: a comment, a title, a description, a tag.
 *
 * <p>It never changes what a reader sees, only what a reader cannot see. Everything removed here is
 * invisible on screen and only ever does one of two jobs: it makes a text look empty or short when
 * it is not, or it splits a word so that nothing reading the text recognises it.
 *
 * <ul>
 *   <li><b>NFC.</b> Vietnamese typed with a decomposing keyboard, or pasted from a macOS file name,
 *       spends up to three code points on one letter — against the length limit, and against a
 *       blocklist written in the composed form.
 *   <li><b>Controls, bidi and format characters are dropped</b> (U+202E turns the rest of a line
 *       around; U+200B splits a word without a visible gap). The zero-width joiner is the
 *       exception: between two pictographs it is what holds 👨‍👩‍👧 together, and only there is it
 *       kept.
 *   <li><b>Whitespace look-alikes become a space</b>, including the Hangul and Braille fillers used
 *       to post a comment that renders as nothing but is not blank to {@code String.isBlank()}.
 *   <li><b>Runs of spaces collapse, lines are trimmed, blank lines collapse to one</b>, and the
 *       whole text is trimmed — so a text that was only invisible characters comes out empty and
 *       fails {@code @NotBlank}.
 *   <li><b>Stacked combining marks are capped.</b> "Zalgo" text piles dozens of marks on a letter
 *       until it draws over the lines around it; no language needs more than a few.
 * </ul>
 *
 * <p>Idempotent, so it is safe on a value that may already have been through it. Called from the
 * compact constructor of the request record, which runs before validation: the length limit is
 * then a limit on what is stored.
 */
public final class TextSanitizer {

    private static final int ZERO_WIDTH_JOINER = 0x200D;

    private static final int EMOJI_PRESENTATION_SELECTOR = 0xFE0F;

    /** Most combining marks one letter keeps. Composed Vietnamese needs none, decomposed two. */
    private static final int MAX_COMBINING_MARKS = 3;

    private TextSanitizer() {
    }

    /** For text that may run over several lines: a comment, a description. */
    public static String sanitize(String raw) {
        return clean(raw, true);
    }

    /** For text that is one line on screen: a title, a tag. Line breaks become spaces. */
    public static String sanitizeLine(String raw) {
        return clean(raw, false);
    }

    private static String clean(String raw, boolean multiline) {
        if (raw == null) {
            return null;
        }
        int[] codePoints = raw.codePoints().toArray();
        StringBuilder out = new StringBuilder(raw.length());

        for (int i = 0; i < codePoints.length; i++) {
            int cp = codePoints[i];
            if (cp == '\r' && i + 1 < codePoints.length && codePoints[i + 1] == '\n') {
                continue;
            }
            if (isLineBreak(cp)) {
                out.append(multiline ? '\n' : ' ');
            } else if (isSpaceLike(cp)) {
                out.append(' ');
            } else if (cp == ZERO_WIDTH_JOINER) {
                if (joinsTwoPictographs(out, codePoints, i)) {
                    out.appendCodePoint(cp);
                }
            } else if (!isInvisible(cp)) {
                out.appendCodePoint(cp);
            }
        }

        // After the drops, not before: removing a character can bring a letter and its combining
        // mark together, and only normalising the result composes them.
        String composed = Normalizer.normalize(out, Normalizer.Form.NFC);
        return tidy(capCombiningMarks(composed));
    }

    private static boolean isLineBreak(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }

    private static boolean isSpaceLike(int cp) {
        return cp == '\t'
                || cp == 0x0B || cp == 0x0C
                || Character.getType(cp) == Character.SPACE_SEPARATOR
                // Hangul fillers and the blank Braille pattern: letters and symbols by category,
                // blank on screen, and the usual way to post a comment that "has content".
                || cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 || cp == 0x2800;
    }

    /**
     * Controls (Cc), format characters (Cf — bidi overrides, zero-width space and non-joiner, word
     * joiner, BOM, soft hyphen, tag characters) and lone surrogates. The combining grapheme joiner
     * is a mark by category but draws nothing and joins nothing a reader can see.
     */
    private static boolean isInvisible(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.SURROGATE
                || cp == 0x034F;
    }

    private static boolean joinsTwoPictographs(StringBuilder out, int[] codePoints, int index) {
        if (out.isEmpty() || index + 1 >= codePoints.length) {
            return false;
        }
        int before = out.codePointBefore(out.length());
        boolean emojiBefore = Character.isExtendedPictographic(before)
                || Character.isEmojiModifier(before)
                || before == EMOJI_PRESENTATION_SELECTOR;
        return emojiBefore && Character.isExtendedPictographic(codePoints[index + 1]);
    }

    private static String capCombiningMarks(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int marks = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int type = Character.getType(cp);
            boolean mark = type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK;
            // Variation selectors are marks too, and an emoji needs its FE0F whatever came before.
            if (mark && cp != EMOJI_PRESENTATION_SELECTOR) {
                marks++;
                if (marks <= MAX_COMBINING_MARKS) {
                    out.appendCodePoint(cp);
                }
            } else {
                marks = 0;
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /**
     * Spaces collapse to one and lines lose their edges; any number of blank lines between two
     * lines of text becomes exactly one, and the text loses its leading and trailing blank lines.
     */
    private static String tidy(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean blankLineSeen = false;
        for (String line : text.split("\n", -1)) {
            String collapsed = collapseSpaces(line);
            if (collapsed.isEmpty()) {
                blankLineSeen = true;
                continue;
            }
            if (!out.isEmpty()) {
                out.append(blankLineSeen ? "\n\n" : "\n");
            }
            out.append(collapsed);
            blankLineSeen = false;
        }
        return out.toString();
    }

    private static String collapseSpaces(String line) {
        StringBuilder out = new StringBuilder(line.length());
        boolean pendingSpace = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }
}
