package com.tiktok.storyservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.service.StoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stories")
@RequiredArgsConstructor
public class StoryController {

    private final StoryService storyService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StoryResponse> create(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody CreateStoryRequest request) {
        return ApiResponse.success(storyService.create(currentUserId, request));
    }

    @GetMapping("/{storyId}")
    public ApiResponse<StoryResponse> getById(@PathVariable String storyId) {
        return ApiResponse.success(storyService.getById(storyId));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<List<StoryResponse>> listByUser(@PathVariable Long userId) {
        return ApiResponse.success(storyService.listByUser(userId));
    }

    @GetMapping("/feed")
    public ApiResponse<List<StoryResponse>> getFeed(@RequestParam List<Long> followedUserIds) {
        return ApiResponse.success(storyService.listFeed(followedUserIds));
    }

    @PostMapping("/{storyId}/views")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordView(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId) {
        storyService.recordView(currentUserId, storyId);
    }

    @GetMapping("/{storyId}/viewers")
    public ApiResponse<List<StoryViewerResponse>> listViewers(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId) {
        return ApiResponse.success(storyService.listViewers(currentUserId, storyId));
    }

    @DeleteMapping("/{storyId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String storyId) {
        storyService.delete(currentUserId, storyId);
        return ApiResponse.success(null);
    }
}
