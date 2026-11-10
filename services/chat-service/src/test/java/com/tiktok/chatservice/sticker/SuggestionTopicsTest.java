package com.tiktok.chatservice.sticker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuggestionTopicsTest {

    /** Words that mean something else in ordinary talk, or that nobody wants a sticker for. */
    private static final Set<String> NEVER = Set.of("ok", "oke", "ừ", "vâng", "dạ", "ngu", "ngủ", "đi", "không", "rồi",
            "chết", "bệnh", "tang", "nợ", "chia tay");

    @Test
    void theBundledTableLoadsAndEveryTopicCanBeAsked() {
        SuggestionTopics topics = SuggestionTopics.load();

        assertThat(topics.all()).isNotEmpty();
        assertThat(topics.all()).extracting(SuggestionTopics.Topic::id).doesNotHaveDuplicates();
        for (SuggestionTopics.Topic topic : topics.all()) {
            assertThat(topic.query()).isNotBlank();
            assertThat(topic.keywords().size() + topic.patterns().size()).isPositive();
            topic.patterns().forEach(Pattern::compile);
            assertThat(topics.find(topic.id())).contains(topic);
        }
    }

    @Test
    void theBundledTableLeavesOutWordsThatWouldSuggestStickersAtTheWrongTime() {
        List<String> keywords = SuggestionTopics.load().all().stream()
                .flatMap(topic -> topic.keywords().stream()).toList();

        assertThat(keywords).doesNotContainAnyElementsOf(NEVER);
    }

    @Test
    void whatTheClientIsToldHidesTheGiphyQuery() {
        SuggestionTopics topics = SuggestionTopics.fromJson("""
                {"topics":[{"id":"happy","query":"happy dance","keywords":["vui"],"patterns":["^(ha){2,}$"]}]}""");

        assertThat(topics.views()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo("happy");
            assertThat(view.keywords()).containsExactly("vui");
            assertThat(view.patterns()).containsExactly("^(ha){2,}$");
        });
        assertThat(topics.find("happy")).get().extracting(SuggestionTopics.Topic::query).isEqualTo("happy dance");
    }

    @Test
    void anUnknownOrMissingIdIsNotFound() {
        SuggestionTopics topics = SuggestionTopics.load();

        assertThat(topics.find("nope")).isEmpty();
        assertThat(topics.find(null)).isEmpty();
    }

    @Test
    void aMalformedTableStopsTheServiceInsteadOfFailingOnAKeystroke() {
        assertThatThrownBy(() -> SuggestionTopics.fromJson("""
                {"topics":[{"id":"a","query":"x","keywords":["x"]},{"id":"a","query":"y","keywords":["y"]}]}"""))
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> SuggestionTopics.fromJson("""
                {"topics":[{"id":"Bad Id","query":"x","keywords":["x"]}]}"""))
                .hasMessageContaining("id");
        assertThatThrownBy(() -> SuggestionTopics.fromJson("""
                {"topics":[{"id":"a","query":" ","keywords":["x"]}]}"""))
                .hasMessageContaining("query");
        assertThatThrownBy(() -> SuggestionTopics.fromJson("""
                {"topics":[{"id":"a","query":"x"}]}"""))
                .hasMessageContaining("matches nothing");
        assertThatThrownBy(() -> SuggestionTopics.fromJson("""
                {"topics":[{"id":"a","query":"x","keywords":[" "]}]}"""))
                .hasMessageContaining("blank keyword");
        assertThatThrownBy(() -> SuggestionTopics.fromJson("""
                {"topics":[{"id":"a","query":"x","patterns":["(unclosed"]}]}"""))
                .hasMessageContaining("bad pattern");
    }
}
