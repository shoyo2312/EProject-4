package com.tiktok.recommendationservice.client;

import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

/**
 * The accounts a viewer has muted, read from user-service's internal endpoint (denied at the
 * gateway, so it takes no token).
 *
 * <p>Fails open, unlike chat-service's block check: a mute is a preference about the viewer's own
 * feed rather than a protection for anyone, and a user-service outage should degrade the feed, not
 * empty it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserMuteClient {

    private static final ParameterizedTypeReference<ApiResponse<List<Long>>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient userServiceRestClient;

    public Set<Long> mutedIds(Long userId) {
        try {
            ApiResponse<List<Long>> response = userServiceRestClient.get()
                    .uri("/api/v1/users/internal/{userId}/muted-ids", userId)
                    .retrieve()
                    .body(RESPONSE_TYPE);
            return response == null || response.data() == null ? Set.of() : Set.copyOf(response.data());
        } catch (RuntimeException e) {
            log.warn("Could not read the mutes of user {}, serving the feed unfiltered: {}", userId, e.getMessage());
            return Set.of();
        }
    }
}
