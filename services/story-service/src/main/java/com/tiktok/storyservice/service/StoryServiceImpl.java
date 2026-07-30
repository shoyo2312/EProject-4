package com.tiktok.storyservice.service;

import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryView;
import com.tiktok.storyservice.exception.NotStoryOwnerException;
import com.tiktok.storyservice.exception.StoryNotFoundException;
import com.tiktok.storyservice.mapper.StoryMapper;
import com.tiktok.storyservice.repository.StoryRepository;
import com.tiktok.storyservice.repository.StoryViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StoryServiceImpl implements StoryService {

    private final StoryRepository storyRepository;
    private final StoryViewRepository storyViewRepository;
    private final StoryMapper storyMapper;

    @Value("${story.ttl-hours}")
    private long ttlHours;

    @Override
    public StoryResponse create(Long userId, CreateStoryRequest request) {
        Instant now = Instant.now();
        Story story = Story.builder()
                .id(Story.newId())
                .userId(userId)
                .mediaUrl(request.mediaUrl())
                .mediaType(request.mediaType())
                .caption(request.caption())
                .viewCount(0)
                .expiresAt(now.plus(Duration.ofHours(ttlHours)))
                .build();

        return storyMapper.toResponse(storyRepository.save(story));
    }

    @Override
    public StoryResponse getById(String storyId) {
        return storyMapper.toResponse(findVisible(storyId));
    }

    @Override
    public List<StoryResponse> listByUser(Long userId) {
        return storyRepository
                .findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now())
                .stream()
                .map(storyMapper::toResponse)
                .toList();
    }

    @Override
    public List<StoryResponse> listFeed(List<Long> followedUserIds) {
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return List.of();
        }

        return storyRepository
                .findByUserIdInAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(followedUserIds, Instant.now())
                .stream()
                .map(storyMapper::toResponse)
                .toList();
    }

    @Override
    public void recordView(Long viewerId, String storyId) {
        Story story = findVisible(storyId);

        if (viewerId.equals(story.getUserId())) {
            return;
        }

        if (storyViewRepository.existsByStoryIdAndViewerId(storyId, viewerId)) {
            return;
        }

        storyViewRepository.save(StoryView.builder()
                .storyId(storyId)
                .viewerId(viewerId)
                .expiresAt(story.getExpiresAt())
                .build());

        story.incrementViewCount();
        storyRepository.save(story);
    }

    @Override
    public List<StoryViewerResponse> listViewers(Long requesterId, String storyId) {
        Story story = findVisible(storyId);

        if (!requesterId.equals(story.getUserId())) {
            throw new NotStoryOwnerException(storyId);
        }

        return storyViewRepository.findByStoryIdOrderByViewedAtDesc(storyId).stream()
                .map(storyMapper::toViewerResponse)
                .toList();
    }

    @Override
    public void delete(Long requesterId, String storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new StoryNotFoundException(storyId));

        if (!requesterId.equals(story.getUserId())) {
            throw new NotStoryOwnerException(storyId);
        }

        story.markDeleted();
        storyRepository.save(story);
    }

    private Story findVisible(String storyId) {
        Story story = storyRepository.findByIdAndDeletedAtIsNull(storyId)
                .orElseThrow(() -> new StoryNotFoundException(storyId));

        if (story.isExpired()) {
            throw new StoryNotFoundException(storyId);
        }

        return story;
    }
}
