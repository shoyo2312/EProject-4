package com.tiktok.interactionservice.client;

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
 * "Are these two accounts mutual followers" — what {@link CommentAudience#FRIENDS} needs and only
 * user-service knows. A copy of video-service's client of the same name, down to the
 * token-forwarding and the fail-closed default.
 *
 * <p>The authenticated endpoint, not the internal one: posting a comment is a REST POST that
 * carries the commenter's bearer token, so the question can be asked as them. chat-service is the
 * caller that cannot do this — it opens conversations from WebSocket frames — which is why the
 * tokenless {@code /users/internal/friendship} exists as well.
 *
 * <p><b>Fails closed</b>, unlike the audience lookup around it: a missing token or an unreachable
 * user-service answers false, so an unconfirmed friendship refuses the comment rather than
 * granting it. The asymmetry is the point — {@code FRIENDS} means "only my friends", and treating
 * an outage as "everyone is my friend" would publish the thread to the platform. Whereas a failure
 * to learn the audience at all leaves commenting open, because that outage would otherwise stop
 * every comment on the platform.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FriendshipClient {

    private final RestClient userServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FriendshipView(boolean friends) {
    }

    public boolean areFriends(Long ownerId, Long commenterId) {
        if (ownerId == null || commenterId == null || ownerId.equals(commenterId)) {
            return false;
        }

        String token = callerAuthorization();
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
            log.warn("Could not confirm friendship with owner {}; refusing the friends-only comment",
                    ownerId, e);
            return false;
        }
    }

    private static String callerAuthorization() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION)
                : null;
    }
}
