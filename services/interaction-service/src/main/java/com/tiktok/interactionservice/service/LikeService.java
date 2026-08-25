package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;

public interface LikeService {

    LikeStatusResponse like(Long videoId, Long currentUserId);

    LikeStatusResponse unlike(Long videoId, Long currentUserId);

    LikeStatusResponse getStatus(Long videoId, Long currentUserId);

    VideoIdPageResponse listLikedVideos(Long currentUserId, String cursor, int size);
}
