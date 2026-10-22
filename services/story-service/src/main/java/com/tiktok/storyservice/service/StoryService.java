package com.tiktok.storyservice.service;

import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.request.StoryUploadUrlRequest;
import com.tiktok.storyservice.dto.response.StoryGroupResponse;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryRingResponse;
import com.tiktok.storyservice.dto.response.StoryUploadUrlResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.StoryVisibility;

import java.time.Instant;
import java.util.List;

/** {@code requesterId} is null for an anonymous caller wherever the endpoint allows one. */
public interface StoryService {

    StoryUploadUrlResponse createUploadUrl(Long userId, StoryUploadUrlRequest request);

    StoryResponse create(Long userId, CreateStoryRequest request);

    StoryResponse getById(Long requesterId, String storyId);

    List<StoryResponse> listByUser(Long requesterId, Long userId);

    List<StoryGroupResponse> feed(Long requesterId, List<Long> followedUserIds);

    List<StoryRingResponse> rings(Long requesterId, List<Long> userIds);

    StoryResponse updateVisibility(Long requesterId, String storyId, StoryVisibility visibility);

    void recordView(Long viewerId, String storyId);

    List<StoryViewerResponse> listViewers(Long requesterId, String storyId, Instant before, Integer size);

    void delete(Long requesterId, String storyId);
}
