package com.tiktok.videoservice.dto.response;

/**
 * The three numbers a profile header shows about a creator. Counted over the same videos the
 * profile grid lists — see {@code VideoRepositoryCustom.sumUserVideoStats}.
 */
public record UserVideoStatsResponse(
        Long userId,
        long videoCount,
        long totalLikes,
        long totalViews
) {
}
