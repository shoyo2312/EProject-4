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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/interactions/videos")
@RequiredArgsConstructor
public class LikeController {

    private final LikeService likeService;

    @PostMapping("/{videoId}/like")
    public ApiResponse<LikeStatusResponse> like(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(likeService.like(videoId, currentUserId));
    }

    @DeleteMapping("/{videoId}/like")
    public ApiResponse<LikeStatusResponse> unlike(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(likeService.unlike(videoId, currentUserId));
    }

    @GetMapping("/{videoId}/like-status")
    public ApiResponse<LikeStatusResponse> getStatus(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(likeService.getStatus(videoId, currentUserId));
    }

    /**
     * Batch form of {@link #getStatus}, for a feed page hydrating hearts for every card it
     * renders in one hop instead of one request per video. Literal path, so it is matched ahead
     * of {@code /{videoId}/like-status} above.
     */
    @GetMapping("/like-status/batch")
    public ApiResponse<List<LikeStatusResponse>> getStatuses(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam List<Long> ids) {
        return ApiResponse.success(likeService.getStatuses(ids, currentUserId));
    }
}
