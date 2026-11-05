package com.tiktok.chatservice.sticker;

import com.tiktok.chatservice.dto.response.SuggestionTopicView;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The words that earn a sticker suggestion while someone types, read from
 * {@code stickers/suggestion-topics.json}. It is a short allowlist on purpose: a suggestion that
 * appears for every message is an annoyance, so only words that name a feeling or a greeting are
 * here, and a word that means something else in ordinary talk ("ok", "ừ", "ngu") is left out.
 *
 * <p>Matching happens in the browser, so the text being typed is never sent anywhere; this class
 * only holds the table and the GIPHY query each topic stands for. Built once at startup — a table
 * that is malformed stops the service instead of failing on a keystroke.
 */
public final class SuggestionTopics {

    public static final String RESOURCE = "stickers/suggestion-topics.json";

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,30}");
    private static final int MAX_QUERY = 50;

    public record Topic(String id, String query, List<String> keywords, List<String> patterns) {
    }

    private record Table(List<Topic> topics) {
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final Map<String, Topic> byId = new LinkedHashMap<>();

    private SuggestionTopics(Table table) {
        if (table == null || table.topics() == null) {
            return;
        }
        for (Topic raw : table.topics()) {
            Topic topic = validated(raw);
            if (byId.put(topic.id(), topic) != null) {
                throw new IllegalStateException("Duplicate suggestion topic " + topic.id());
            }
        }
    }

    private static Topic validated(Topic raw) {
        if (raw == null || raw.id() == null || !ID.matcher(raw.id()).matches()) {
            throw new IllegalStateException("Suggestion topic needs an id like 'happy'");
        }
        if (raw.query() == null || raw.query().isBlank() || raw.query().length() > MAX_QUERY) {
            throw new IllegalStateException("Suggestion topic " + raw.id() + " needs a query of 1-" + MAX_QUERY + " characters");
        }
        List<String> keywords = raw.keywords() == null ? List.of() : raw.keywords();
        List<String> patterns = raw.patterns() == null ? List.of() : raw.patterns();
        if (keywords.isEmpty() && patterns.isEmpty()) {
            throw new IllegalStateException("Suggestion topic " + raw.id() + " matches nothing");
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                throw new IllegalStateException("Suggestion topic " + raw.id() + " has a blank keyword");
            }
        }
        for (String pattern : patterns) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException | NullPointerException e) {
                throw new IllegalStateException("Suggestion topic " + raw.id() + " has a bad pattern: " + pattern, e);
            }
        }
        return new Topic(raw.id(), raw.query().trim(), List.copyOf(keywords), List.copyOf(patterns));
    }

    /** No file on the classpath: nothing is suggested. */
    public static SuggestionTopics load() {
        try (InputStream in = SuggestionTopics.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            return in == null ? new SuggestionTopics(null) : new SuggestionTopics(MAPPER.readValue(in, Table.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Builds the table from JSON text — for a test that needs a particular topic. */
    public static SuggestionTopics fromJson(String json) {
        return new SuggestionTopics(MAPPER.readValue(json, Table.class));
    }

    public List<Topic> all() {
        return List.copyOf(byId.values());
    }

    public Optional<Topic> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** What the client needs to match: no query, which is the server's business. */
    public List<SuggestionTopicView> views() {
        return byId.values().stream()
                .map(topic -> new SuggestionTopicView(topic.id(), topic.keywords(), topic.patterns()))
                .toList();
    }
}
