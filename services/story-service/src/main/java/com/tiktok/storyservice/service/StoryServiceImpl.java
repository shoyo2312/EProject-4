package com.tiktok.storyservice.service;

import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryView;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.exception.NotStoryOwnerException;
import com.tiktok.storyservice.exception.StoryNotFoundException;
import com.tiktok.storyservice.mapper.StoryMapper;
import com.tiktok.storyservice.repository.StoryRepository;
import com.tiktok.storyservice.repository.StoryViewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

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
        Story story = Story.pending(Story.newId(), userId, request.mediaUrl(), request.mediaType(),
                request.caption(), StoryVisibility.PUBLIC, null, Instant.now());

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

        // The unique (storyId, viewerId) index decides who counted first; checking before inserting
        // let two concurrent views through the check, and one of them then failed on the index.
        try {
            storyViewRepository.insert(StoryView.builder()
                    .storyId(storyId)
                    .viewerId(viewerId)
                    .expiresAt(story.getExpiresAt())
                    .build());
        } catch (DuplicateKeyException e) {
            return;
        }
        storyRepository.incrementViewCount(storyId);
    }

    @Override
    public List<StoryViewerResponse> listViewers(Long requesterId, String storyId) {
        Story story = findVisible(storyId);

        if (!requesterId.equals(story.getUserId())) {
            throw new NotStoryOwnerException(storyId);
        }

        return storyViewRepository.findByStoryIdAndViewedAtBeforeOrderByViewedAtDesc(storyId, Instant.now(), PageRequest.of(0, 50)).stream()
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
