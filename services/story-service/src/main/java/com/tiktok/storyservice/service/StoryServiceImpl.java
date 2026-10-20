package com.tiktok.storyservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.request.StoryUploadUrlRequest;
import com.tiktok.storyservice.dto.response.StoryUploadUrlResponse;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryView;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.NotStoryOwnerException;
import com.tiktok.storyservice.exception.UploadNotFoundException;
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
    private final StoryMedia storyMedia;
    private final StoryCaptionModeration captionModeration;

    @Value("${story.ttl-hours}")
    private long ttlHours;

    // ponytail: no per-account limit on how many upload URLs or stories one account may ask for;
    // the signed byte limit bounds each upload. Reuse video-service's UploadRateLimiter if abused.
    @Override
    public StoryUploadUrlResponse createUploadUrl(Long userId, StoryUploadUrlRequest request) {
        return storyMedia.presignUpload(userId, request.contentType());
    }

    @Override
    public StoryResponse create(Long userId, CreateStoryRequest request) {
        String key = storyMedia.keyOf(userId, request.mediaUrl());
        StoryMediaType mediaType = storyMedia.typeOf(key);
        if (!storyMedia.exists(key)) {
            throw new UploadNotFoundException();
        }
        // Before the insert: a refused caption leaves nothing behind, so the upload can be posted again.
        ModerationVerdict textVerdict = captionModeration.screen(request.caption());

        Story story = Story.pending(Story.newId(), userId, key, mediaType, request.caption(),
                request.visibility(), textVerdict, Instant.now());
        try {
            return storyMapper.toResponse(storyRepository.insert(story));
        } catch (DuplicateKeyException e) {
            // The unique media key: one upload backs one story.
            throw new ForeignUploadException();
        }
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
