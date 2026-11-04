package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.request.CreatorVideoSort;
import com.tiktok.analyticsservice.dto.response.CreatorOverviewResponse;
import com.tiktok.analyticsservice.dto.response.CreatorVideoDetailResponse;
import com.tiktok.analyticsservice.dto.response.CreatorVideoStatsResponse;
import com.tiktok.analyticsservice.dto.response.CreatorViewersResponse;

import java.util.List;

/**
 * TikTok Studio, for the creator asking and nobody else. Every window is the last {@code days}
 * calendar days in Vietnam, today included.
 */
public interface CreatorAnalyticsService {

    CreatorOverviewResponse overview(long creatorId, int days);

    List<CreatorVideoStatsResponse> topVideos(long creatorId, int days, CreatorVideoSort sort, int limit);

    /** Throws {@code VIDEO_NOT_FOUND} for a video the creator did not upload. */
    CreatorVideoDetailResponse video(long creatorId, String videoId, int days);

    CreatorViewersResponse viewers(long creatorId, int days);
}
