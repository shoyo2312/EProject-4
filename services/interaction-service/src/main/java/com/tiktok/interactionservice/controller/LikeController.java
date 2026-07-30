package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.service.LikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/interactions/videos/{videoId}")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @PostMapping("/like")
    public ApiResponse<LikeStatusResponse> like(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(likeService.like(videoId, currentUserId));
    }

    @DeleteMapping("/like")
    public ApiResponse<LikeStatusResponse> unlike(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(likeService.unlike(videoId, currentUserId));
    }

    @GetMapping("/like-status")
    public ApiResponse<LikeStatusResponse> getStatus(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(likeService.getStatus(videoId, currentUserId));
    }
}
