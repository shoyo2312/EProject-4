package com.tiktok.chatservice.realtime;

import com.tiktok.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads follower/following counts from user-service and total-likes from video-service, and
 * merges the two into one frame per user — same "read from the service that owns it" rule as
 * {@link InteractionCountsClient}, over two owners instead of one.
 */
@Slf4j
@Component
public class UserStatsClient {

    private static final ParameterizedTypeReference<ApiResponse<List<FollowCountsRow>>> FOLLOW_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<List<LikesRow>>> LIKES_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient userServiceClient;
    private final RestClient videoServiceClient;

    public UserStatsClient(RestClient.Builder builder,
                           @Value("${downstream.user-service-uri}") String userServiceUri,
                           @Value("${downstream.video-service-uri}") String videoServiceUri) {
        this.userServiceClient = builder.baseUrl(userServiceUri).build();
        this.videoServiceClient = builder.baseUrl(videoServiceUri).build();
    }

    /**
     * @return one frame per requested id that at least one of the two services answered for.
     *     Either half missing (a service down, or an id it does not know) just omits that half's
     *     fields — see {@link UserFrame} — rather than dropping the id from the batch.
     */
    public List<UserFrame> fetch(List<String> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        Map<String, FollowCountsRow> follow = safeFetch(() -> fetchFollowCounts(userIds), "user-service");
        Map<String, LikesRow> likes = safeFetch(() -> fetchLikes(userIds), "video-service");

        return userIds.stream()
                .filter(id -> follow.containsKey(id) || likes.containsKey(id))
                .map(id -> {
                    FollowCountsRow f = follow.get(id);
                    LikesRow l = likes.get(id);
                    return UserFrame.stats(id,
                            f == null ? null : f.followerCount(),
                            f == null ? null : f.followingCount(),
                            l == null ? null : l.totalLikes());
                })
                .toList();
    }

    private Map<String, FollowCountsRow> fetchFollowCounts(List<String> userIds) {
        ApiResponse<List<FollowCountsRow>> response = userServiceClient.get()
                .uri(uri -> uri.path("/api/v1/users/stats/batch")
                        .queryParam("ids", String.join(",", userIds))
                        .build())
                .retrieve()
                .body(FOLLOW_TYPE);
        Map<String, FollowCountsRow> byId = new HashMap<>();
        if (response != null && response.data() != null) {
            for (FollowCountsRow row : response.data()) {
                byId.put(String.valueOf(row.userId()), row);
            }
        }
        return byId;
    }

    private Map<String, LikesRow> fetchLikes(List<String> userIds) {
        ApiResponse<List<LikesRow>> response = videoServiceClient.get()
                .uri(uri -> uri.path("/api/v1/videos/users/stats/batch")
                        .queryParam("ids", String.join(",", userIds))
                        .build())
                .retrieve()
                .body(LIKES_TYPE);
        Map<String, LikesRow> byId = new HashMap<>();
        if (response != null && response.data() != null) {
            for (LikesRow row : response.data()) {
                byId.put(String.valueOf(row.userId()), row);
            }
        }
        return byId;
    }

    /**
     * One service being unreachable must not sink the other half of the batch — a viewer's
     * follower count is still worth sending live even the moment total-likes cannot be read.
     */
    private <T> Map<String, T> safeFetch(java.util.function.Supplier<Map<String, T>> call, String service) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("Could not read counts from {} for this window, that half stays stale: {}",
                    service, e.getMessage());
            return Map.of();
        }
    }

    private record FollowCountsRow(Long userId, long followerCount, long followingCount) {
    }

    private record LikesRow(Long userId, long videoCount, long totalLikes, long totalViews) {
    }
}
