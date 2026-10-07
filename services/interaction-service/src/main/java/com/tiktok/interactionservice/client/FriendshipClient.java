package com.tiktok.interactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.exception.PrivacyCheckUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

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
 *
 * <p>{@link #canSeeFollowerContent} is the exception, and asks the tokenless internal endpoint:
 * the Reposts tab is public, so its caller may be anonymous with no token to forward.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FriendshipClient {

    private final RestClient userServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FriendshipView(boolean friends, boolean following, boolean privateAccount) {
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

    /**
     * Whether {@code viewerId} may see what {@code ownerId} keeps for followers: true for a public
     * account, and for a private one the viewer follows. {@code viewerId} is null for an anonymous
     * caller.
     *
     * <p>Fails closed like {@link #areFriends}, but out loud: an answer nobody could give throws
     * {@link PrivacyCheckUnavailableException}, a 503, instead of returning false. The tab stays
     * shut either way, but false reads as "private, and you do not follow", and the caller turns
     * that into a 403 saying the account is private — for every account, public ones included,
     * for as long as user-service is down.
     */
    public boolean canSeeFollowerContent(Long ownerId, Long viewerId) {
        ApiResponse<FriendshipView> response;
        try {
            response = userServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/friendship")
                            .queryParam("userB", ownerId)
                            .queryParamIfPresent("userA", Optional.ofNullable(viewerId))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (RestClientException e) {
            log.warn("Could not ask whether user {} is private or followed by {}; their reposts are unavailable",
                    ownerId, viewerId, e);
            throw new PrivacyCheckUnavailableException(e);
        }
        if (response == null || response.data() == null) {
            throw new PrivacyCheckUnavailableException(
                    new IllegalStateException("user-service answered the friendship lookup without data"));
        }
        return !response.data().privateAccount() || response.data().following();
    }

    private static String callerAuthorization() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION)
                : null;
    }
}
