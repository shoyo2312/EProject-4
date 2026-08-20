package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.service.ViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/interactions/videos/{videoId}")
@RequiredArgsConstructor
public class ViewController {

    private final ViewService viewService;

    /**
     * Needs a token, unlike watching the video itself: deduplication is per viewer, so an
     * anonymous view has no identity to deduplicate against and would let one browser inflate
     * a count without limit. Anonymous playback simply does not count for now.
     */
    @PostMapping("/view")
    public ApiResponse<ViewResponse> recordView(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(viewService.recordView(videoId, currentUserId));
    }
}
