package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.request.WatchRequest;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.dto.response.WatchResponse;
import com.tiktok.interactionservice.service.ViewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * Sent once when playback ends — closing the player, scrolling away, navigating out — not
     * per progress tick: this is one row of training data per session, and a per-second ping
     * would flood the topic with rows describing the same session.
     *
     * <p>Separate from /view rather than a field on it because the two fire at different moments
     * and answer different questions: /view is the counter and is deduplicated per day, this is
     * the label and counts every session, replays included.
     */
    @PostMapping("/watch")
    public ApiResponse<WatchResponse> recordWatch(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @Valid @RequestBody WatchRequest request) {
        return ApiResponse.success(viewService.recordWatch(videoId, currentUserId, request));
    }
}
