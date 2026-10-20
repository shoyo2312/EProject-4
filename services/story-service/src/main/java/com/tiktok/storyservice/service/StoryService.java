package com.tiktok.storyservice.service;

import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.request.StoryUploadUrlRequest;
import com.tiktok.storyservice.dto.response.StoryUploadUrlResponse;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;

import java.util.List;

public interface StoryService {

    StoryUploadUrlResponse createUploadUrl(Long userId, StoryUploadUrlRequest request);

    StoryResponse create(Long userId, CreateStoryRequest request);

    StoryResponse getById(String storyId);

    List<StoryResponse> listByUser(Long userId);

    List<StoryResponse> listFeed(List<Long> followedUserIds);

    void recordView(Long viewerId, String storyId);

    List<StoryViewerResponse> listViewers(Long requesterId, String storyId);

    void delete(Long requesterId, String storyId);
}
