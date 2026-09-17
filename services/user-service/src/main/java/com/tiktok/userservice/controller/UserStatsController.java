package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.UserStatsCountsResponse;
import com.tiktok.userservice.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
@Tag(name = "User stats", description = "Follower/following counts for internal callers")
public class UserStatsController {

    private final UserProfileService userProfileService;

    @GetMapping("/batch")
    @Operation(summary = "Follower and following counts for up to 100 user ids",
            description = "Ids with no profile are absent from the answer; duplicates collapse. "
                    + "More ids than the cap is 400 TOO_MANY_PROFILE_IDS.")
    public ApiResponse<List<UserStatsCountsResponse>> batch(@RequestParam List<Long> ids) {
        return ApiResponse.success(userProfileService.getStatsByUserIds(ids));
    }
}
