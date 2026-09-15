package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.RepostContextResponse;
import com.tiktok.interactionservice.dto.response.RepostStatusResponse;
import com.tiktok.interactionservice.service.RepostService;
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
public class RepostController {

    private final RepostService repostService;

    @PostMapping("/{videoId}/repost")
    public ApiResponse<RepostStatusResponse> repost(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(repostService.repost(videoId, currentUserId));
    }

    @DeleteMapping("/{videoId}/repost")
    public ApiResponse<RepostStatusResponse> unrepost(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(repostService.unrepost(videoId, currentUserId));
    }

    /**
     * Batch form for the repost badge, same shape as like-status/batch: one hop to decorate
     * every card a feed page renders instead of one request per video.
     */
    @GetMapping("/repost-context/batch")
    public ApiResponse<List<RepostContextResponse>> getContexts(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam List<Long> ids) {
        return ApiResponse.success(repostService.getContexts(ids, currentUserId));
    }
}
