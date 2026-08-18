package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.VideoResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VideoService {

    VideoResponse publish(Long userId, CreateVideoRequest request);

    VideoResponse getById(Long requesterId, String videoId);

    /**
     * @param cursor the previous page's {@code nextCursor}, or null to start at the newest video
     * @param size   null falls back to the configured default; above the configured maximum it is
     *               clamped down to it rather than refused
     */
    CursorPage<VideoResponse> getFeed(String cursor, Integer size);

    Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable);

    void delete(Long requesterId, String videoId);
}
