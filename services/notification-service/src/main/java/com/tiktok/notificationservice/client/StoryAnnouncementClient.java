package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;

/**
 * Whether a story is still worth announcing, asked of story-service's internal endpoint right
 * before its followers are told. The activation event names the story and its owner but not the
 * story's state, and by the time the fan-out runs the story may be deleted, expired, narrowed to
 * PRIVATE, or its owner banned.
 *
 * <p>Not {@link BlockClient}'s fail-open: an empty answer ends the event as handled, so only
 * story-service's own "not found" may produce one — a 404 carrying its {@code STORY_NOT_FOUND}
 * code. A 404 from anywhere else (a {@code STORY_SERVICE_URI} that lands on another service
 * answers {@code NOT_FOUND}) says nothing about the story and fails like everything else. When
 * story-service gives no answer at all (refused, unreachable, timed out) kafka-lib treats it as an
 * outage and retries until it does; a 5xx, or a 404 without that code, gets three retries and then
 * the DLT.
 */
@Component
@RequiredArgsConstructor
public class StoryAnnouncementClient {

    private static final String STORY_NOT_FOUND = "STORY_NOT_FOUND";

    private final RestClient storyServiceRestClient;

    /**
     * @param userId     the story's owner
     * @param visibility who may watch it now: PUBLIC, FRIENDS or PRIVATE
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Announcement(Long userId, String visibility) {
    }

    /** Empty when story-service says the story is gone (404 STORY_NOT_FOUND). */
    public Optional<Announcement> announcement(String storyId) {
        ApiResponse<Announcement> response;
        try {
            response = storyServiceRestClient.get()
                    .uri("/api/v1/stories/internal/{storyId}/announcement", storyId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (HttpClientErrorException.NotFound notFound) {
            if (isStoryNotFound(notFound)) {
                return Optional.empty();
            }
            throw notFound;
        }
        if (response == null || response.data() == null) {
            throw new IllegalStateException("story-service answered storyId=" + storyId + " without an announcement");
        }
        return Optional.of(response.data());
    }

    /** A body that cannot be read is not story-service's own answer. */
    private static boolean isStoryNotFound(HttpClientErrorException notFound) {
        try {
            ApiResponse<?> body = notFound.getResponseBodyAs(ApiResponse.class);
            return body != null && STORY_NOT_FOUND.equals(body.code());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }
}
