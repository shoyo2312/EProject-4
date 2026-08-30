package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;

import java.util.List;

public interface LikeService {

    LikeStatusResponse like(Long videoId, Long currentUserId);

    LikeStatusResponse unlike(Long videoId, Long currentUserId);

    LikeStatusResponse getStatus(Long videoId, Long currentUserId);

    /** Same answer as {@link #getStatus}, one per id, in the order given. */
    List<LikeStatusResponse> getStatuses(List<Long> videoIds, Long currentUserId);

    VideoIdPageResponse listLikedVideos(Long currentUserId, String cursor, int size);
}
