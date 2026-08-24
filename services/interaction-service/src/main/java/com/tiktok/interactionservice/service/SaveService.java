package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.SaveStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;

public interface SaveService {

    SaveStatusResponse save(Long videoId, Long currentUserId);

    SaveStatusResponse unsave(Long videoId, Long currentUserId);

    SaveStatusResponse getStatus(Long videoId, Long currentUserId);

    VideoIdPageResponse listSavedVideos(Long currentUserId, String cursor, int size);
}
