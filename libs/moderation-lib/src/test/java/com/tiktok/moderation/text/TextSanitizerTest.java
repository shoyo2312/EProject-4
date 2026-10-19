package com.tiktok.moderation.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TextSanitizerTest {

    @Test
    void null_staysNull() {
        assertThat(TextSanitizer.sanitize(null)).isNull();
        assertThat(TextSanitizer.sanitizeLine(null)).isNull();
    }

    /** Typed with a decomposing keyboard: e + U+0302 (circumflex) + U+0323 (dot below). */
    @Test
    void decomposedVietnamese_isComposed() {
        String decomposed = "Việt Nam";

        String stored = TextSanitizer.sanitize(decomposed);

        assertThat(stored).isEqualTo("Việt Nam");
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(8);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "​",   // zero-width space
            "‌",   // zero-width non-joiner
            "⁠",   // word joiner
            "﻿",   // byte order mark
            "­",   // soft hyphen
            "‮",   // right-to-left override
            "⁦",   // left-to-right isolate
            "‏",   // right-to-left mark
            "\u0007",   // bell
            "͏"    // combining grapheme joiner
    })
    void invisibleCharacters_areDropped(String invisible) {
        assertThat(TextSanitizer.sanitize("đ" + invisible + "m")).isEqualTo("đm");
    }

    @Test
    void tagCharacters_areDropped() {
        String smuggled = "hi" + new String(Character.toChars(0xE0068)) + new String(Character.toChars(0xE0069));

        assertThat(TextSanitizer.sanitize(smuggled)).isEqualTo("hi");
    }

    @Test
    void zeroWidthJoiner_holdsAnEmojiSequenceTogether() {
        String family = "👨‍👩‍👧";

        assertThat(TextSanitizer.sanitize("nhà mình " + family)).isEqualTo("nhà mình " + family);
    }

    @Test
    void zeroWidthJoiner_insideAWord_isDropped() {
        assertThat(TextSanitizer.sanitize("đ‍m")).isEqualTo("đm");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ㅤ", "ᅟ", "ᅠ", "ﾠ", "⠀", " ", "　", " ", "\t"})
    void blankLookAlikes_becomeSpaces_soATextOfThemIsEmpty(String filler) {
        assertThat(TextSanitizer.sanitize(filler + filler + filler)).isEmpty();
        assertThat(TextSanitizer.sanitize("a" + filler + "b")).isEqualTo("a b");
    }

    @Test
    void spacesCollapse_andLinesAreTrimmed() {
        assertThat(TextSanitizer.sanitize("  hay    quá  \n   thật  ")).isEqualTo("hay quá\nthật");
    }

    @Test
    void blankLines_collapseToOne_andEdgesAreTrimmed() {
        assertThat(TextSanitizer.sanitize("\n\nmột\n\n\n\n\nhai\r\nba\rbốn\n\n"))
                .isEqualTo("một\n\nhai\nba\nbốn");
    }

    @Test
    void singleLine_turnsLineBreaksIntoSpaces() {
        assertThat(TextSanitizer.sanitizeLine("Tiêu đề\n\ncó xuống dòng")).isEqualTo("Tiêu đề có xuống dòng");
    }

    @Test
    void stackedCombiningMarks_areCapped() {
        String zalgo = "a" + "́".repeat(30) + "b";

        String stored = TextSanitizer.sanitize(zalgo);

        assertThat(stored.codePointCount(0, stored.length())).isLessThanOrEqualTo(5);
        assertThat(stored).startsWith("á").endsWith("b");
    }

    @Test
    void emojiPresentationSelectors_andKeycaps_survive() {
        assertThat(TextSanitizer.sanitize("❤️ 1️⃣")).isEqualTo("❤️ 1️⃣");
    }

    @Test
    void isIdempotent() {
        String messy = "  ‮Đ​m ㅤㅤ 👨‍👩‍👧\n\n\n\nViệt " + "́".repeat(9);

        String once = TextSanitizer.sanitize(messy);

        assertThat(TextSanitizer.sanitize(once)).isEqualTo(once);
        assertThat(TextSanitizer.sanitizeLine(once)).isEqualTo(TextSanitizer.sanitizeLine(TextSanitizer.sanitizeLine(once)));
    }
}
