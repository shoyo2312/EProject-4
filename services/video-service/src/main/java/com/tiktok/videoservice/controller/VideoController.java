package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
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

    @GetMapping("/feed")
    public ApiResponse<Page<VideoResponse>> getFeed(Pageable pageable) {
        return ApiResponse.success(videoService.getFeed(pageable));
    }

    @GetMapping("/{videoId}")
    public ApiResponse<VideoResponse> getById(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId) {
        return ApiResponse.success(videoService.getById(currentUserId, videoId));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<Page<VideoResponse>> listByUser(@PathVariable Long userId, Pageable pageable) {
        return ApiResponse.success(videoService.listByUser(userId, pageable));
    }

    @DeleteMapping("/{videoId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId) {
        videoService.delete(currentUserId, videoId);
        return ApiResponse.success(null);
    }
}
