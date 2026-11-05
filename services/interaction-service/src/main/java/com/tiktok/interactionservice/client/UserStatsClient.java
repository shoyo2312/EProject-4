package com.tiktok.interactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.exception.FollowerCountUnavailableException;
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
import java.util.stream.Collectors;

/**
 * How many followers each of these accounts has — what Studio's "posted by accounts with N
 * followers" filter needs and only user-service knows. {@code GET /users/stats/batch}: counts only,
 * no viewer needed, 100 ids a call.
 *
 * <p>Fails out loud: a count nobody could give is a 503, not a silent empty page that would read as
 * "no comments from such accounts". An id with no profile is simply absent from the answer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatsClient {

    private static final int MAX_IDS_PER_CALL = 100;

    private final RestClient userServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatsView(Long userId, long followerCount) {
    }

    public Map<Long, Long> followerCounts(Collection<Long> userIds) {
        List<Long> ids = userIds.stream().distinct().toList();
        Map<Long, Long> counts = new HashMap<>();
        for (int i = 0; i < ids.size(); i += MAX_IDS_PER_CALL) {
            List<Long> chunk = ids.subList(i, Math.min(i + MAX_IDS_PER_CALL, ids.size()));
            try {
                ApiResponse<List<StatsView>> response = userServiceRestClient.get()
                        .uri("/api/v1/users/stats/batch?ids={ids}",
                                chunk.stream().map(String::valueOf).collect(Collectors.joining(",")))
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {
                        });
                if (response == null || response.data() == null) {
                    throw new FollowerCountUnavailableException(null);
                }
                response.data().forEach(view -> counts.put(view.userId(), view.followerCount()));
            } catch (RestClientException e) {
                log.warn("Could not load follower counts for {} accounts", chunk.size(), e);
                throw new FollowerCountUnavailableException(e);
            }
        }
        return counts;
    }
}
