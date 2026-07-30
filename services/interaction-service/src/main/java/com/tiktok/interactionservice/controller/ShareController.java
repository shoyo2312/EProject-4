package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.InteractionCountResponse;
import com.tiktok.interactionservice.dto.response.ShareResponse;
import com.tiktok.interactionservice.service.CounterCacheService;
import com.tiktok.interactionservice.service.ShareService;
import com.tiktok.interactionservice.service.VideoCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/interactions/videos/{videoId}")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;
    private final CounterCacheService counterCacheService;

    @PostMapping("/share")
    public ApiResponse<ShareResponse> share(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(shareService.share(videoId, currentUserId));
    }

    @GetMapping("/counts")
    public ApiResponse<InteractionCountResponse> counts(@PathVariable Long videoId) {
        VideoCounts counts = counterCacheService.getCounts(videoId);
        return ApiResponse.success(new InteractionCountResponse(
                videoId, counts.likeCount(), counts.commentCount(), counts.shareCount()));
    }
}
