package com.tiktok.userservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a "Download your data" archive needs from other services, each read whole by following its
 * cursor to the end. Nothing here falls back: an unreachable service throws, the build fails, and
 * the worker tries again after its lease — an archive quietly missing a section is worse than one
 * that arrives a few minutes late.
 */
@Component
@RequiredArgsConstructor
public class DataExportSources {

    /** Pages per listing, at 50–100 rows each. A guard against a cursor that never ends, not a quota. */
    private static final int MAX_PAGES = 2_000;

    private final RestClient videoServiceRestClient;
    private final RestClient interactionServiceRestClient;
    private final RestClient chatServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Video(String id, String title, String description, List<String> tags, String status,
                        String visibility, Integer durationSeconds, long viewCount, long likeCount,
                        Long commentCount, String thumbnailUrl, String hlsUrl, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Activity(Long videoId, Instant at) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conversation(String id, List<Long> participantIds, Instant createdAt) {
    }

    /**
     * {@code type} is null for a message from before chat had types, and TEXT otherwise; a STICKER has
     * no {@code content}, only the catalog entry chat-service attached. VIDEO carries its id and caption,
     * with no card or media URLs.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, Long senderId, String content, Instant sentAt, String type, Sticker sticker, String videoId) {

        public Message(String id, Long senderId, String content, Instant sentAt, String type, Sticker sticker) {
            this(id, senderId, content, sentAt, type, sticker, null);
        }

        public Message(String id, Long senderId, String content, Instant sentAt) {
            this(id, senderId, content, sentAt, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sticker(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VideoPage(List<Video> items, String nextCursor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ActivityPage(List<Activity> items, String nextCursor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MessagePage(List<Message> items, String nextCursor) {
    }

    private record Page<T>(List<T> items, String nextCursor) {
    }

    public List<Video> videosOf(Long userId) {
        return readAll(cursor -> {
            VideoPage page = data(videoServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/videos/internal/users/{userId}/videos")
                            .queryParam("page", cursor == null ? "0" : cursor)
                            .queryParam("size", 50)
                            .build(userId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<VideoPage>>() {
                    }));
            return new Page<>(page.items(), page.nextCursor());
        });
    }

    /** {@code kind} is one of {@code likes}, {@code saves}, {@code reposts}. */
    public List<Activity> activityOf(Long userId, String kind) {
        return readAll(cursor -> {
            ActivityPage page = data(interactionServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/interactions/internal/users/{userId}/{kind}")
                            .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                            .queryParam("size", 100)
                            .build(userId, kind))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<ActivityPage>>() {
                    }));
            return new Page<>(page.items(), page.nextCursor());
        });
    }

    public List<Conversation> conversationsOf(Long userId) {
        return data(chatServiceRestClient.get()
                .uri("/api/v1/conversations/internal/users/{userId}", userId)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<Conversation>>>() {
                }));
    }

    /** Newest first, as chat-service pages them. */
    public List<Message> messagesOf(String conversationId, Long viewerId) {
        return readAll(cursor -> {
            MessagePage page = data(chatServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/conversations/internal/{conversationId}/messages")
                            .queryParam("viewerId", viewerId)
                            .queryParamIfPresent("cursor", Optional.ofNullable(cursor))
                            .queryParam("size", 100)
                            .build(conversationId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<MessagePage>>() {
                    }));
            return new Page<>(page.items(), page.nextCursor());
        });
    }

    private static <T> List<T> readAll(Function<String, Page<T>> fetch) {
        List<T> all = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            Page<T> page = fetch.apply(cursor);
            if (page.items() != null) {
                all.addAll(page.items());
            }
            cursor = page.nextCursor();
            if (cursor == null) {
                return all;
            }
        }
        throw new IllegalStateException("Listing did not end after " + MAX_PAGES + " pages");
    }

    private static <T> T data(ApiResponse<T> response) {
        if (response == null || response.data() == null) {
            throw new IllegalStateException("Downstream answered without data");
        }
        return response.data();
    }
}
