package com.tiktok.videoservice.client;

import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of a request's private owners the viewer follows — what a private account's PUBLIC videos
 * need and only user-service knows. One call for all of them, so a page costs one round trip
 * rather than one per owner.
 *
 * <p>No token: the internal prefix is denied at the gateway and permitted in user-service, the
 * arrangement {@link BlockClient} uses. Unlike BlockClient it <b>fails closed</b>, like
 * {@link FriendshipClient}: a lookup that fails answers "follows nobody", which hides a private
 * owner's videos rather than showing them to strangers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowClient {

    /** user-service's cap on one batch — its UserProfileService.MAX_BATCH_IDS. */
    private static final int MAX_IDS_PER_CALL = 100;

    private final RestClient userServiceRestClient;

    public Set<Long> followedAmong(Long followerId, Collection<Long> ownerIds) {
        if (followerId == null || ownerIds.isEmpty()) {
            return Set.of();
        }

        List<Long> ids = new ArrayList<>(ownerIds);
        Set<Long> followed = new HashSet<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_CALL) {
            List<Long> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_CALL, ids.size()));
            try {
                ApiResponse<List<Long>> response = userServiceRestClient.get()
                        .uri(uri -> uri.path("/api/v1/users/internal/following")
                                .queryParam("followerId", followerId)
                                .queryParam("ids", chunk.toArray())
                                .build())
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {
                        });
                if (response != null && response.data() != null) {
                    followed.addAll(response.data());
                }
            } catch (RestClientException e) {
                log.warn("Could not ask user-service which of {} user {} follows; hiding their videos",
                        chunk, followerId, e);
            }
        }
        return followed;
    }
}
