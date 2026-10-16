package com.tiktok.moderation.rules;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.Flag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordFilterTest {

    @Test
    void aKeywordFound_isACreatorHold() {
        List<Flag> flags = KeywordFilter.match("Spoiler: cuối phim nam chính chết", List.of("spoiler", "giảm giá"));

        assertThat(flags).singleElement().satisfies(flag -> {
            assertThat(flag.category()).isEqualTo(Category.CREATOR_FILTER);
            assertThat(flag.severity()).isEqualTo(Flag.HOLD);
            assertThat(flag.source()).isEqualTo(Flag.Source.CREATOR);
            assertThat(flag.rule()).isEqualTo("term:spoiler");
        });
    }

    @Test
    void keywordsMatchWholeSyllables_andDiacriticsTheWayTheBlocklistDoes() {
        assertThat(KeywordFilter.match("spoilers everywhere", List.of("spoiler"))).isEmpty();
        assertThat(KeywordFilter.match("một tách cà phê", List.of("tạch"))).isEmpty();
        assertThat(KeywordFilter.match("thi tạch rồi", List.of("tạch"))).hasSize(1);
        // Written without diacritics, it catches every spelling.
        assertThat(KeywordFilter.match("Giảm GIÁ sốc", List.of("giam gia"))).hasSize(1);
    }

    @Test
    void nothingToMatch_isNoFlag() {
        assertThat(KeywordFilter.match("hello", List.of())).isEmpty();
        assertThat(KeywordFilter.match("hello", null)).isEmpty();
        assertThat(KeywordFilter.match("  ", List.of("hello"))).isEmpty();
    }

    /** user-service validates what it stores; an old or odd row must not fail a comment. */
    @Test
    void aKeywordThatCannotMatch_isSkipped() {
        assertThat(KeywordFilter.match("🔥 hay", List.of("🔥", "hay"))).extracting(Flag::rule)
                .containsExactly("term:hay");
    }
}
