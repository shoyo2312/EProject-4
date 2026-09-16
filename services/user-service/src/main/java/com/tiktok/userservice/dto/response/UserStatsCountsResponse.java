package com.tiktok.userservice.dto.response;

/** One row of {@code GET /api/v1/users/stats/batch} — counts only, no PII, so it needs no viewer. */
public record UserStatsCountsResponse(Long userId, long followerCount, long followingCount) {
}
