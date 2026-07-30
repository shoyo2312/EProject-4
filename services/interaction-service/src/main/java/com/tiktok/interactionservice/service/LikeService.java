package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.LikeStatusResponse;

public interface LikeService {

    LikeStatusResponse like(Long videoId, Long currentUserId);

    LikeStatusResponse unlike(Long videoId, Long currentUserId);

    LikeStatusResponse getStatus(Long videoId, Long currentUserId);
}
