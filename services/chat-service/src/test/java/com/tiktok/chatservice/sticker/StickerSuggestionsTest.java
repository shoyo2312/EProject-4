package com.tiktok.chatservice.sticker;

import com.tiktok.chatservice.dto.response.StickerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StickerSuggestionsTest {

    private static final SuggestionTopics TOPICS = SuggestionTopics.fromJson("""
            {"topics":[
              {"id":"happy","query":"happy","keywords":["vui"]},
              {"id":"sad","query":"sad","keywords":["buồn"]}
            ]}""");

    private final AtomicLong now = new AtomicLong(1_000);
    private GiphyClient giphy;
    private StickerSuggestions suggestions;

    @BeforeEach
    void setUp() {
        giphy = mock(GiphyClient.class);
        suggestions = new StickerSuggestions(TOPICS, giphy, now::get);
    }

    private static List<StickerView> stickers(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> StickerCatalog.giphy(prefix + "abcde" + i, prefix + i)).toList();
    }

    private void advance(Duration by) {
        now.addAndGet(by.toNanos());
    }

    @Test
    void asksGiphyOncePerTopicNotOncePerRequest() {
        when(giphy.stickers("happy", 0, StickerSuggestions.POOL)).thenReturn(stickers("h", 20));

        for (int i = 0; i < 5; i++) {
            assertThat(suggestions.suggestions("happy")).isNotEmpty();
        }

        verify(giphy, times(1)).stickers("happy", 0, StickerSuggestions.POOL);
    }

    @Test
    void showsAFewOfThePoolAndNeverSomethingElse() {
        List<StickerView> pool = stickers("h", 20);
        when(giphy.stickers("happy", 0, StickerSuggestions.POOL)).thenReturn(pool);

        List<StickerView> shown = suggestions.suggestions("happy");

        assertThat(shown).hasSize(StickerSuggestions.SHOWN).doesNotHaveDuplicates();
        assertThat(pool).containsAll(shown);
    }

    @Test
    void aSmallPoolIsShownWhole() {
        List<StickerView> pool = stickers("h", 3);
        when(giphy.stickers("happy", 0, StickerSuggestions.POOL)).thenReturn(pool);

        assertThat(suggestions.suggestions("happy")).containsExactlyInAnyOrderElementsOf(pool);
    }

    @Test
    void anUnknownTopicIsNothingAndCostsNoCall() {
        assertThat(suggestions.suggestions("nope")).isEmpty();
        assertThat(suggestions.suggestions(null)).isEmpty();

        verify(giphy, never()).stickers(anyString(), anyInt(), anyInt());
    }

    @Test
    void aTopicThatCouldNotLoadIsNotRetriedOnEveryRequest() {
        when(giphy.stickers(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(suggestions.suggestions("happy")).isEmpty();
        assertThat(suggestions.suggestions("happy")).isEmpty();
        assertThat(suggestions.suggestions("happy")).isEmpty();
        verify(giphy, times(1)).stickers("happy", 0, StickerSuggestions.POOL);

        advance(StickerSuggestions.RETRY.plusSeconds(1));
        when(giphy.stickers("happy", 0, StickerSuggestions.POOL)).thenReturn(stickers("h", 5));

        assertThat(suggestions.suggestions("happy")).hasSize(5);
    }

    @Test
    void aFailedRefreshKeepsTheStickersItHad() {
        List<StickerView> pool = stickers("h", 6);
        when(giphy.stickers("happy", 0, StickerSuggestions.POOL)).thenReturn(pool, List.of());
        assertThat(suggestions.suggestions("happy")).hasSize(6);

        advance(StickerSuggestions.TTL.plusSeconds(1));

        assertThat(suggestions.suggestions("happy")).containsExactlyInAnyOrderElementsOf(pool);
        verify(giphy, times(2)).stickers("happy", 0, StickerSuggestions.POOL);

        // And it waits before asking again, rather than asking on every request while GIPHY is down.
        assertThat(suggestions.suggestions("happy")).hasSize(6);
        verify(giphy, times(2)).stickers("happy", 0, StickerSuggestions.POOL);
    }

    @Test
    void aStalePoolIsReplacedOnceGiphyAnswers() {
        when(giphy.stickers("happy", 0, StickerSuggestions.POOL)).thenReturn(stickers("a", 4), stickers("b", 4));
        assertThat(suggestions.suggestions("happy")).extracting(StickerView::name).allMatch(n -> n.startsWith("a"));

        advance(StickerSuggestions.TTL.plusSeconds(1));

        assertThat(suggestions.suggestions("happy")).extracting(StickerView::name).allMatch(n -> n.startsWith("b"));
    }

    @Test
    void refreshAllWarmsEveryTopicAndOnlyThoseThatNeedIt() {
        when(giphy.stickers(anyString(), eq(0), eq(StickerSuggestions.POOL))).thenReturn(stickers("x", 5));

        suggestions.refreshAll();
        suggestions.refreshAll();

        verify(giphy, times(1)).stickers("happy", 0, StickerSuggestions.POOL);
        verify(giphy, times(1)).stickers("sad", 0, StickerSuggestions.POOL);
    }

    @Test
    void refreshAllSurvivesATopicThatThrows() {
        when(giphy.stickers("happy", 0, StickerSuggestions.POOL)).thenThrow(new IllegalStateException("boom"));
        when(giphy.stickers("sad", 0, StickerSuggestions.POOL)).thenReturn(stickers("s", 5));

        suggestions.refreshAll();

        assertThat(suggestions.suggestions("sad")).hasSize(5);
    }
}
