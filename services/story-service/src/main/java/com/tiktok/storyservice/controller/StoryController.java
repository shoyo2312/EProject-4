package com.tiktok.storyservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.request.StoryUploadUrlRequest;
import com.tiktok.storyservice.dto.request.UpdateStoryVisibilityRequest;
import com.tiktok.storyservice.dto.response.StoryGroupResponse;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryUploadUrlResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.service.StoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/** {@code currentUserId} is null on the endpoints SecurityConfig opens to anonymous callers. */
@RestController
@RequestMapping("/api/v1/stories")
@RequiredArgsConstructor
public class StoryController {

    private final StoryService storyService;

    @PostMapping("/upload-url")
    public ApiResponse<StoryUploadUrlResponse> createUploadUrl(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody StoryUploadUrlRequest request) {
        return ApiResponse.success(storyService.createUploadUrl(currentUserId, request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StoryResponse> create(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody CreateStoryRequest request) {
        return ApiResponse.success(storyService.create(currentUserId, request));
    }

    @GetMapping("/feed")
    public ApiResponse<List<StoryGroupResponse>> feed(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) List<Long> followedUserIds) {
        return ApiResponse.success(storyService.feed(currentUserId, followedUserIds));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<List<StoryResponse>> listByUser(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(storyService.listByUser(currentUserId, userId));
    }

    @GetMapping("/{storyId}")
    public ApiResponse<StoryResponse> getById(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId) {
        return ApiResponse.success(storyService.getById(currentUserId, storyId));
    }

    @PatchMapping("/{storyId}/visibility")
    public ApiResponse<StoryResponse> updateVisibility(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId,
            @Valid @RequestBody UpdateStoryVisibilityRequest request) {
        return ApiResponse.success(storyService.updateVisibility(currentUserId, storyId, request.visibility()));
    }

    @PostMapping("/{storyId}/views")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordView(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId) {
        storyService.recordView(currentUserId, storyId);
    }

    /** Newest first; pass the last {@code viewedAt} as {@code before} for the next page. */
    @GetMapping("/{storyId}/viewers")
    public ApiResponse<List<StoryViewerResponse>> listViewers(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId,
            @RequestParam(required = false) Instant before,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(storyService.listViewers(currentUserId, storyId, before, size));
    }

    @DeleteMapping("/{storyId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId) {
        storyService.delete(currentUserId, storyId);
        return ApiResponse.success(null);
    }
}
