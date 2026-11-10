package com.tiktok.chatservice.sticker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.dto.response.StickerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks stickers up on GIPHY for the picker. The API key stays here: the browser asks this service,
 * never GIPHY. GIPHY's free key allows 100 calls an hour, so every answer is remembered for ten
 * minutes by query and page, and the cache is capped so a stream of odd queries cannot grow it.
 *
 * <p>A search that fails, or a service with no key, answers with no stickers rather than an error:
 * the rest of the chat does not depend on it.
 */
@Component
public class GiphyClient {

    static final int MAX_LIMIT = 30;
    static final int MAX_OFFSET = 200;
    static final int MAX_QUERY = 50;
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int MAX_ENTRIES = 200;
    private static final Logger log = LoggerFactory.getLogger(GiphyClient.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(List<Item> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String id, String title) {
    }

    private record Cached(List<StickerView> stickers, long at) {
    }

    private final GiphyProperties properties;
    private final RestClient restClient;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public GiphyClient(GiphyProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    /** Stickers matching {@code query}, or the trending ones when it is blank. */
    public List<StickerView> stickers(String query, int offset, int limit) {
        if (!properties.enabled()) {
            return List.of();
        }
        String q = query == null ? "" : query.trim();
        if (q.length() > MAX_QUERY) {
            q = q.substring(0, MAX_QUERY);
        }
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        int safeOffset = Math.max(0, Math.min(offset, MAX_OFFSET));
        String key = q.toLowerCase(Locale.ROOT) + "|" + safeOffset + "|" + safeLimit;

        Cached hit = cache.get(key);
        long now = System.nanoTime();
        if (hit != null && now - hit.at() < TTL.toNanos()) {
            return hit.stickers();
        }
        List<StickerView> fresh = fetch(q, safeOffset, safeLimit);
        if (fresh == null) {
            return hit != null ? hit.stickers() : List.of();
        }
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new Cached(fresh, now));
        return fresh;
    }

    private List<StickerView> fetch(String q, int offset, int limit) {
        try {
            Result result = restClient.get().uri(uri -> {
                uri.path(q.isEmpty() ? "/v1/stickers/trending" : "/v1/stickers/search");
                if (!q.isEmpty()) {
                    uri.queryParam("q", q);
                }
                return uri.queryParam("api_key", properties.apiKey()).queryParam("limit", limit)
                        .queryParam("offset", offset).queryParam("rating", "g").build();
            }).retrieve().body(Result.class);
            if (result == null || result.data() == null) {
                return List.of();
            }
            return result.data().stream()
                    .filter(item -> item.id() != null && item.id().matches("[A-Za-z0-9]{5,40}"))
                    .map(item -> StickerCatalog.giphy(item.id(),
                            item.title() == null || item.title().isBlank() ? "GIPHY sticker" : item.title()))
                    .toList();
        } catch (RestClientException e) {
            // The URL carries the key, so log the class of failure, not the exception text.
            log.warn("GIPHY lookup failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }
}
