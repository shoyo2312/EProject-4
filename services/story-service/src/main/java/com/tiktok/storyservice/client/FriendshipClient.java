package com.tiktok.storyservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * How a viewer relates to a page of story owners, in one internal call per 100 owners: whether
 * either of them blocked the other, whether the owner is private, whether the viewer follows them,
 * and whether they are mutual followers. Asked of user-service rather than kept in a local copy fed
 * by user.privacy-events: a copy only knows what changed while story-service was listening, so an
 * account made private before then would read as public and its stories would reach strangers.
 *
 * <p>Fails closed, owner by owner: an owner user-service gave no answer for — the call failed, came
 * back without data, or left them out — is {@link Relationship#UNKNOWN}, which hides their stories
 * from this viewer until user-service answers again. The block rides in the same answer, so there
 * is no separate "no block" to fall back to: where the block check this replaced failed open, a
 * failure here shows nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FriendshipClient {

    /** user-service's cap on one batch — its UserProfileService.MAX_BATCH_IDS. */
    private static final int MAX_IDS_PER_CALL = 100;

    private final RestClient userServiceRestClient;

    /** The owner as the viewer sees them; an anonymous viewer blocks, follows and befriends no one. */
    public record Relationship(boolean blocked, boolean privateAccount, boolean following, boolean friends) {

        /** A private account the viewer does not follow, of which {@code StoryAudience} shows nothing. */
        public static final Relationship UNKNOWN = new Relationship(false, true, false, false);
    }

    /** An entry of user-service's answer. It names its owner, so entries are matched by id, not position. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Answer(Long userId, boolean blocked, boolean privateAccount, boolean following, boolean friends) {

        Relationship relationship() {
            return new Relationship(blocked, privateAccount, following, friends);
        }
    }

    /**
     * Every owner asked about is in the result, answered or {@link Relationship#UNKNOWN}.
     *
     * <p>ponytail: chunks are asked one after the other, so the tray's 500 followed owners cost at
     * most five calls in sequence. Asking them in parallel is the upgrade if that shows in latency.
     *
     * @param viewerId null for an anonymous viewer
     */
    public Map<Long, Relationship> relationships(Long viewerId, Collection<Long> ownerIds) {
        List<Long> owners = List.copyOf(ownerIds);
        Map<Long, Relationship> relationships = new HashMap<>();
        for (int from = 0; from < owners.size(); from += MAX_IDS_PER_CALL) {
            relationships.putAll(ask(viewerId, owners.subList(from, Math.min(from + MAX_IDS_PER_CALL, owners.size()))));
        }
        return relationships;
    }

    private Map<Long, Relationship> ask(Long viewerId, List<Long> owners) {
        String userIds = owners.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, Relationship> answered = new HashMap<>();
        RestClientException failure = null;
        try {
            ApiResponse<List<Answer>> response = userServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/relationships")
                            .queryParamIfPresent("viewerId", Optional.ofNullable(viewerId))
                            .queryParam("userIds", userIds)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response != null && response.data() != null) {
                response.data().forEach(answer -> answered.put(answer.userId(), answer.relationship()));
            }
        } catch (RestClientException e) {
            failure = e;
        }

        // One line for the chunk, not one per owner: a user-service that is down fails them all.
        long unanswered = owners.stream().filter(owner -> !answered.containsKey(owner)).count();
        if (unanswered > 0) {
            log.warn("user-service did not say how {} relates to {} of {} owners; hiding their stories",
                    viewerId, unanswered, owners.size(), failure);
        }

        // Only the owners asked about: an entry for anyone else must not overwrite another chunk's answer.
        Map<Long, Relationship> relationships = new HashMap<>();
        owners.forEach(owner -> relationships.put(owner, answered.getOrDefault(owner, Relationship.UNKNOWN)));
        return relationships;
    }
}
