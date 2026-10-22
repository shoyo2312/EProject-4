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
 * <p>Not {@link BlockClient}'s fail-open: an empty answer ends the event as handled, so only a 404
 * may produce one. Anything else — story-service down, a timeout, a 5xx — propagates, the claim is
 * released and kafka-lib redelivers until the story is confirmed.
 */
@Component
@RequiredArgsConstructor
public class StoryAnnouncementClient {

    private final RestClient storyServiceRestClient;

    /**
     * @param userId     the story's owner
     * @param visibility who may watch it now: PUBLIC, FRIENDS or PRIVATE
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Announcement(Long userId, String visibility) {
    }

    /** Empty when story-service says the story is gone (404). */
    public Optional<Announcement> announcement(String storyId) {
        ApiResponse<Announcement> response;
        try {
            response = storyServiceRestClient.get()
                    .uri("/api/v1/stories/internal/{storyId}/announcement", storyId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (HttpClientErrorException.NotFound gone) {
            return Optional.empty();
        }
        if (response == null || response.data() == null) {
            throw new IllegalStateException("story-service answered storyId=" + storyId + " without an announcement");
        }
        return Optional.of(response.data());
    }
}
