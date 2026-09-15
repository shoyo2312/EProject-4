package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.UserStatsCountsResponse;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Counts for many users in one request, for chat-service's realtime fan-out — same shape and
 * reason as interaction-service's {@code VideoCountsController}: it learns from Kafka that a set
 * of accounts moved and needs current numbers, and one request per account would be one HTTP
 * round trip per follow.
 *
 * <p>Its own endpoint rather than reusing {@code GET /users?ids=}: that one needs a viewer id to
 * filter blocked accounts out of a profile response, and a follower/following count is neither
 * PII nor a profile — there is nothing to filter, and no viewer to filter it for.
 */
@RestController
@RequestMapping("/api/v1/users/stats")
@RequiredArgsConstructor
public class UserStatsController {

    /** Mirrors interaction-service's VideoCountsController cap — the ids come from a query string. */
    private static final int MAX_BATCH_SIZE = 50;

    private final UserProfileRepository userProfileRepository;

    @GetMapping("/batch")
    public ApiResponse<List<UserStatsCountsResponse>> batch(@RequestParam List<Long> ids) {
        List<Long> capped = ids.stream().filter(Objects::nonNull).distinct().limit(MAX_BATCH_SIZE).toList();
        List<UserProfile> profiles = userProfileRepository.findByUserIdInAndDeletedAtIsNull(capped);
        return ApiResponse.success(profiles.stream()
                .map(p -> new UserStatsCountsResponse(p.getUserId(), p.getFollowerCount(), p.getFollowingCount()))
                .toList());
    }
}
