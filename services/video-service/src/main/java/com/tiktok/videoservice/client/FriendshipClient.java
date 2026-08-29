package com.tiktok.videoservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * "Are these two accounts mutual followers" — the one thing FRIENDS visibility needs and only
 * user-service knows. Fail-closed: a missing token, an unreachable user-service or any non-200
 * answers false, so a lookup failure hides a FRIENDS video rather than leaking it.
 *
 * <p>user-service's API requires a JWT (unlike video-service's own GET endpoints), so the
 * viewer's inbound bearer token is forwarded — the question is asked as the viewer. With no
 * request-bound token (anonymous viewer, or a non-request thread) the call is skipped.
 *
 * <p>ponytail: reads the token off the thread-bound request context rather than threading it
 * through every service signature. Fine for synchronous MVC; revisit if a caller goes async.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FriendshipClient {

    private final RestClient userServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FriendshipView(boolean friends) {
    }

    public boolean areFriends(Long ownerId, Long viewerId) {
        if (ownerId == null || viewerId == null || ownerId.equals(viewerId)) {
            return false;
        }

        String token = currentBearerToken();
        if (token == null) {
            return false;
        }

        try {
            ApiResponse<FriendshipView> response = userServiceRestClient.get()
                    .uri("/api/v1/users/{userId}/friendship", ownerId)
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response != null && response.data() != null && response.data().friends();
        } catch (RestClientException e) {
            log.warn("Could not confirm friendship with owner {} against user-service; hiding the FRIENDS video",
                    ownerId, e);
            return false;
        }
    }

    private String currentBearerToken() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        return attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }
}
