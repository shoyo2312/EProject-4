package com.tiktok.moderation.rules;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.text.TextNormalizer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlocklistTest {

    private static final Blocklist LIST = Blocklist.parse(List.of(
            "# a comment",
            "version: test-1",
            "",
            "cặc        | PROFANITY | 2",
            "lồn        | PROFANITY | 2",
            "buồi       | PROFANITY | 2",
            "đĩ         | INSULT    | 2",
            "dm         | PROFANITY | 2",
            "dit me may | PROFANITY | 3",
            "ngu như bò | INSULT    | 2",
            "3 que      | HATE      | 2",
            "dm         | PROFANITY | 3"
    ), "test");

    private static List<Flag> match(String text) {
        return LIST.match(TextNormalizer.normalize(text));
    }

    @Test
    void readsItsVersion() {
        assertThat(LIST.version()).isEqualTo("test-1");
    }

    /** The reason terms with diacritics match only with them: their folded forms are everyday words. */
    @Test
    void aTermWrittenWithDiacritics_doesNotMatchItsEverydayTwin() {
        assertThat(match("các bạn ơi")).isEmpty();
        assertThat(match("to lớn quá")).isEmpty();
        assertThat(match("buổi sáng vui vẻ")).isEmpty();
        assertThat(match("đi chơi không")).isEmpty();
        assertThat(match("cac ban, lon, buoi, di")).isEmpty();
    }

    @Test
    void aTermWrittenWithDiacritics_matchesItselfHoweverItIsDressedUp() {
        assertThat(match("CẶC")).extracting(Flag::rule).containsExactly("term:cặc");
        assertThat(match("l.ồ.n")).extracting(Flag::rule).containsExactly("term:lồn");
        assertThat(match("buồiiii")).extracting(Flag::rule).containsExactly("term:buồi");
    }

    @Test
    void aTermWrittenWithDiacritics_isNotHiddenByAnInvisibleMarkAfterIt() {
        assertThat(match("con đĩ︎")).extracting(Flag::rule).containsExactly("term:đĩ");
        assertThat(match("cái lồn️ gì")).extracting(Flag::rule).containsExactly("term:lồn");
    }

    /** How a hashtag has to write a phrase, and how a comment dodging the list chooses to. */
    @Test
    void aTermOfSeveralSyllables_matchesWrittenAsOneWord() {
        assertThat(match("#ngunhưbò")).extracting(Flag::rule).containsExactly("term:ngu như bò");
        assertThat(match("ditmemay")).extracting(Flag::severity).containsExactly(3);
        // Joined, the 3 sits next to letters and reads as one, in the term as in the text.
        assertThat(match("3que")).extracting(Flag::category).containsExactly(Category.HATE);
    }

    @Test
    void aTermWrittenAsOneWord_isStillAWholeWord() {
        assertThat(match("ngunhưbòđấy")).isEmpty();
        assertThat(match("ngunhubo")).as("written with diacritics, so not its folded twin").isEmpty();
    }

    @Test
    void anAsciiTerm_matchesEverySpelling() {
        for (String spelling : List.of("dm", "Đm", "ĐM", "đ.m", "đ m", "dmmmm", "ｄｍ")) {
            assertThat(match(spelling)).as(spelling).extracting(Flag::rule).containsExactly("term:dm");
        }
    }

    @Test
    void matchesWholeSyllablesOnly() {
        assertThat(match("admin dmca")).isEmpty();
        assertThat(match("ngu như bòn bon")).isEmpty();
    }

    @Test
    void multiSyllableTerms_matchAcrossLeetAndFolding() {
        assertThat(match("đ1t m3 m4y")).extracting(Flag::severity).containsExactly(3);
        assertThat(match("Ngu như bò")).extracting(Flag::category).containsExactly(Category.INSULT);
        assertThat(match("bọn 3 que")).extracting(Flag::category).containsExactly(Category.HATE);
    }

    @Test
    void aTermListedTwice_keepsItsHighestSeverity() {
        assertThat(match("dm")).extracting(Flag::severity).containsExactly(3);
    }

    @Test
    void eachTermIsReportedOnce() {
        assertThat(match("dm dm đ.m")).hasSize(1);
    }

    @Test
    void theShippedList_loads_andMatchesWhatItsHeaderPromises() {
        Blocklist shipped = Blocklist.load(new ClassPathResource("moderation/blocklist-vi.txt"));

        assertThat(shipped.version()).isNotBlank();
        assertThat(shipped.size()).isGreaterThan(50);
        assertThat(shipped.match(TextNormalizer.normalize("các bạn ăn hạt óc chó buổi sáng nhé"))).isEmpty();
        assertThat(shipped.match(TextNormalizer.normalize("vào nhà cái uy tín"))).isNotEmpty();
    }

    @Test
    void anUnknownCategory_failsTheLoad() {
        assertThatThrownBy(() -> Blocklist.parse(List.of("version: 1", "xyz | RUDE | 2"), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("RUDE");
    }

    @Test
    void aSeverityOtherThanTwoOrThree_failsTheLoad() {
        assertThatThrownBy(() -> Blocklist.parse(List.of("version: 1", "xyz | SPAM | 1"), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("severity");
    }

    @Test
    void aMissingVersion_failsTheLoad() {
        assertThatThrownBy(() -> Blocklist.parse(List.of("xyz | SPAM | 2"), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }

    @Test
    void aMalformedLine_failsTheLoad() {
        assertThatThrownBy(() -> Blocklist.parse(List.of("version: 1", "xyz SPAM 2"), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2");
    }
    @Test
    void aTermOfOnlyPunctuation_failsTheLoad() {
        assertThatThrownBy(() -> Blocklist.parse(List.of("version: 1", "!!! | SPAM | 2"), "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no letters or digits");
    }

    @Test
    void flagsFromAFile_areRaisedByTheRules() {
        assertThat(match("dm")).extracting(Flag::source).containsExactly(Flag.Source.RULES);
    }

    @Test
    void aListBuiltInCode_matchesLikeAFile_andCarriesItsSource() {
        Blocklist keywords = Blocklist.of("creator", List.of(
                new Blocklist.Term("spoiler", Category.CREATOR_FILTER, Flag.HOLD),
                new Blocklist.Term("tạch", Category.CREATOR_FILTER, Flag.HOLD)), Flag.Source.CREATOR);

        assertThat(keywords.match(TextNormalizer.normalize("SP0ILER phần 2 đây")))
                .extracting(Flag::rule, Flag::source)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("term:spoiler", Flag.Source.CREATOR));
        // Written with diacritics, so not its everyday twin.
        assertThat(keywords.match(TextNormalizer.normalize("tách trà nóng"))).isEmpty();
        assertThat(keywords.match(TextNormalizer.normalize("thi tạch rồi"))).hasSize(1);
    }

    @Test
    void aListBuiltInCode_refusesWhatCouldNeverMatch() {
        assertThatThrownBy(() -> Blocklist.of("x", List.of(new Blocklist.Term("🔥🔥", Category.SPAM, Flag.HOLD)),
                Flag.Source.RULES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Blocklist.of("x", List.of(new Blocklist.Term("abc", Category.SPAM, Flag.NOTE)),
                Flag.Source.RULES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("severity");
    }

    @Test
    void merge_addsTerms_andTheMoreSevereEntryWins() {
        Blocklist added = Blocklist.of("a", List.of(
                new Blocklist.Term("lồn", Category.PROFANITY, Flag.REJECT),
                new Blocklist.Term("cút đi", Category.INSULT, Flag.HOLD),
                new Blocklist.Term("dm", Category.PROFANITY, Flag.HOLD)), Flag.Source.RULES);

        Blocklist merged = LIST.merge(added, "test-1+a");

        assertThat(merged.version()).isEqualTo("test-1+a");
        assertThat(merged.match(TextNormalizer.normalize("lồn"))).extracting(Flag::severity).containsExactly(3);
        assertThat(merged.match(TextNormalizer.normalize("cút đi"))).hasSize(1);
        // Shipped as 3; an admin's 2 does not lower it.
        assertThat(merged.match(TextNormalizer.normalize("dm"))).extracting(Flag::severity).containsExactly(3);
        assertThat(LIST.match(TextNormalizer.normalize("cút đi"))).as("the original is untouched").isEmpty();
    }

    @Test
    void without_removesTheTermThatKeysTheSame_andOnlyThatOne() {
        Blocklist trimmed = LIST.without(List.of("Ngu  như BÒ", "dm"), "test-1-x");

        assertThat(trimmed.match(TextNormalizer.normalize("ngu như bò"))).isEmpty();
        assertThat(trimmed.match(TextNormalizer.normalize("dm"))).isEmpty();
        assertThat(trimmed.match(TextNormalizer.normalize("cặc"))).hasSize(1);
        // "lon" keys the folded map; the shipped "lồn" lives in the accented one and stays.
        assertThat(LIST.without(List.of("lon"), "v").match(TextNormalizer.normalize("lồn"))).hasSize(1);
    }

    @Test
    void contains_andKeyOf_readTermsTheWayTheMatcherDoes() {
        assertThat(LIST.contains("NGU NHƯ BÒ")).isTrue();
        assertThat(LIST.contains("ngu nhu bo")).as("a different key: the folded twin").isFalse();
        assertThat(LIST.contains("không có")).isFalse();
        assertThat(LIST.find("DM")).get().extracting(Blocklist.Term::severity).isEqualTo(Flag.REJECT);
        assertThat(LIST.find("không có")).isEmpty();
        assertThat(Blocklist.keyOf("  Nhà   Cái ")).isEqualTo("nhà cái");
        assertThat(Blocklist.keyOf("nha cai")).isEqualTo("nha cai");
        assertThat(Blocklist.isMatchable("...")).isFalse();
        assertThatThrownBy(() -> Blocklist.keyOf("..."))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
