package com.tiktok.storyservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Everything a story's audience needs to know about viewer and owner, in one internal call:
 * whether the owner is private, whether the viewer follows them, and whether they are mutual
 * followers. Asked of user-service rather than kept in a local copy fed by user.privacy-events:
 * a copy only knows what changed while story-service was listening, so an account made private
 * before then would read as public and its stories would reach strangers.
 *
 * <p>Fails closed: an unanswered lookup is {@link Relationship#UNKNOWN}, which hides the owner's
 * stories from this viewer until user-service answers again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FriendshipClient {

    private final RestClient userServiceRestClient;

    /** The owner as the viewer sees them; an anonymous viewer is never a follower or a friend. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relationship(boolean friends, boolean following, boolean privateAccount) {

        public static final Relationship UNKNOWN = new Relationship(false, false, true);
    }

    /** @param viewerId null for an anonymous viewer */
    public Relationship relationship(Long viewerId, Long ownerId) {
        try {
            ApiResponse<Relationship> response = userServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/friendship")
                            .queryParamIfPresent("userA", Optional.ofNullable(viewerId))
                            .queryParam("userB", ownerId)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.data() == null) {
                return Relationship.UNKNOWN;
            }
            return response.data();
        } catch (RestClientException e) {
            log.warn("Could not ask user-service how {} relates to owner {}; hiding the owner's stories",
                    viewerId, ownerId, e);
            return Relationship.UNKNOWN;
        }
    }
}
