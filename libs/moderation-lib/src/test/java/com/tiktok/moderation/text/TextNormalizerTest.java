package com.tiktok.moderation.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {

    @Test
    void splitsIntoLowerCaseSyllables_keepingDiacritics() {
        NormalizedText text = TextNormalizer.normalize("Hay QUÁ, bạn ơi!!!");

        assertThat(text.accented().getFirst()).containsExactly("hay", "quá", "bạn", "ơi");
        assertThat(text.folded().getFirst()).containsExactly("hay", "qua", "ban", "oi");
    }

    @Test
    void folding_readsDBarAsD() {
        assertThat(TextNormalizer.fold("Đường đi")).isEqualTo("Duong di");
    }

    @Test
    void fullWidthAndMathematicalLetters_becomePlain() {
        assertThat(TextNormalizer.normalize("ｄｍ 𝐝𝐦").folded().getFirst()).containsExactly("dm", "dm");
    }

    @Test
    void cyrillicAndGreekLookAlikes_becomeLatin() {
        // Cyrillic о and с, Greek ο.
        assertThat(TextNormalizer.normalize("lоl cас gοοd").accented().getFirst()).containsExactly("lol", "cac", "good");
    }

    @Test
    void capitalsDrawnAsDBar_readAsDBar_andFoldToD() {
        // Icelandic Ð and African Ɖ; lower-cased they are ð and ɖ, which no term has.
        NormalizedText text = TextNormalizer.normalize("ÐỊT Ɖm ðit");

        assertThat(text.accented().getFirst()).containsExactly("địt", "đm", "đit");
        assertThat(text.folded().getFirst()).containsExactly("dit", "dm", "dit");
    }

    @Test
    void leetspeak_isDecodedInsideWords_butNumbersStayNumbers() {
        assertThat(TextNormalizer.normalize("d1t m3 2026 c@c").accented().getFirst())
                .containsExactly("dit", "me", "2026", "cac");
    }

    @Test
    void symbols_stayPunctuationOutsideWords() {
        assertThat(TextNormalizer.normalize("@minh hay! $100").accented().getFirst())
                .containsExactly("minh", "hay", "100");
    }

    @Test
    void marksLeftLooseAfterComposition_areDropped_butVietnameseKeepsItsOwn() {
        // An invisible variation selector after the word; an underline under each letter.
        assertThat(TextNormalizer.normalize("lồn︎ l̲ồ̲n̲").accented().getFirst())
                .containsExactly("lồn", "lồn");
        // Typed decomposed, the diacritics compose rather than go.
        assertThat(TextNormalizer.normalize("lồn").accented().getFirst()).containsExactly("lồn");
    }

    @Test
    void longRepeats_squeeze_butDoublesStay() {
        assertThat(TextNormalizer.normalize("nguuuu đmmmm xoong good").accented().getFirst())
                .containsExactly("ngu", "đm", "xoong", "good");
    }

    @Test
    void wordsSpelledOutLetterByLetter_areAlsoReadJoined() {
        NormalizedText text = TextNormalizer.normalize("đ.m v c l thật");

        assertThat(text.accented()).containsExactly(
                List.of("đ", "m", "v", "c", "l", "thật"),
                List.of("đmvcl", "thật"));
    }

    @Test
    void aLoneLetter_isNotAJoinedVariant() {
        assertThat(TextNormalizer.normalize("e ơi").accented()).hasSize(1);
    }

    @Test
    void fingerprint_ignoresCasePunctuationDiacriticsAndSpacing() {
        assertThat(TextNormalizer.fingerprint("Hay   quá!!!")).isEqualTo(TextNormalizer.fingerprint("hay qua"));
    }

    @Test
    void fingerprint_ofEmojiOnlyText_isTheEmoji() {
        assertThat(TextNormalizer.fingerprint("🔥 🔥")).isEqualTo("🔥🔥");
        assertThat(TextNormalizer.fingerprint("🔥")).isNotEqualTo(TextNormalizer.fingerprint("😂"));
    }
}
