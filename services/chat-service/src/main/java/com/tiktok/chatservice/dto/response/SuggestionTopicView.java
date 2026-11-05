package com.tiktok.chatservice.dto.response;

import java.util.List;

/**
 * A topic as a client matches it against what the user is typing. The GIPHY query behind it stays
 * on the server: the client names a topic by {@code id}, and what the user typed never leaves the
 * browser.
 *
 * @param keywords words or phrases, as written (with diacritics); the client normalises both sides
 * @param patterns whole-word regular expressions over the normalised word, for "hahaha" and the like
 */
public record SuggestionTopicView(String id, List<String> keywords, List<String> patterns) {
}
