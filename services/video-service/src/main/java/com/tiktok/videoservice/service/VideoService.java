package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.response.VideoResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VideoService {

    VideoResponse publish(Long userId, CreateVideoRequest request);

    VideoResponse getById(Long requesterId, String videoId);

    Page<VideoResponse> getFeed(Pageable pageable);

    Page<VideoResponse> listByUser(Long userId, Pageable pageable);

    void delete(Long requesterId, String videoId);
}
