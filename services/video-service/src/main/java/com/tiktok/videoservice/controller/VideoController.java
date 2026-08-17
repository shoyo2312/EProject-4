package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.service.VideoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VideoResponse> publish(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody CreateVideoRequest request) {
        return ApiResponse.success(videoService.publish(currentUserId, request));
    }

    /**
     * Cursor-paged, unlike the profile listing below: pass back the previous response's
     * {@code nextCursor} to continue, omit it to start at the newest video, stop when it comes back
     * null. No {@code page} parameter and no total — see {@link CursorPage} for why.
     */
    @GetMapping("/feed")
    public ApiResponse<CursorPage<VideoResponse>> getFeed(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(videoService.getFeed(cursor, size));
    }

    @GetMapping("/{videoId}")
    public ApiResponse<VideoResponse> getById(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId) {
        return ApiResponse.success(videoService.getById(currentUserId, videoId));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<Page<VideoResponse>> listByUser(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId,
            Pageable pageable) {
        return ApiResponse.success(videoService.listByUser(currentUserId, userId, pageable));
    }

    @DeleteMapping("/{videoId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId) {
        videoService.delete(currentUserId, videoId);
        return ApiResponse.success(null);
    }
}
