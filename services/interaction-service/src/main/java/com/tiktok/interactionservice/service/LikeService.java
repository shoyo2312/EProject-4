package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;

import java.util.List;

public interface LikeService {

    LikeStatusResponse like(Long videoId, Long currentUserId);

    LikeStatusResponse unlike(Long videoId, Long currentUserId);

    LikeStatusResponse getStatus(Long videoId, Long currentUserId);

    /**
     * Same answer as {@link #getStatus}, one per distinct id, in the order given. Capped: each id
     * costs a point read, and the ids arrive in a query string, so anything past one page's worth
     * is dropped rather than served.
     */
    List<LikeStatusResponse> getStatuses(List<Long> videoIds, Long currentUserId);

    VideoIdPageResponse listLikedVideos(Long currentUserId, String cursor, int size);
}
