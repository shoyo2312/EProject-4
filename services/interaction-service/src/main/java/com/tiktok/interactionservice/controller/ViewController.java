package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.request.ViewRequest;
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
     * Sent once when playback starts, carrying the playId of that playback, and counted per play
     * — replaying a video counts again.
     *
     * <p>Needs a token, unlike watching the video itself: the per-viewer rate limit is what keeps
     * a counted-every-play number honest, and an anonymous view has no identity to limit against.
     * Anonymous playback simply does not count for now.
     */
    @PostMapping("/view")
    public ApiResponse<ViewResponse> recordView(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @Valid @RequestBody ViewRequest request) {
        return ApiResponse.success(viewService.recordView(videoId, currentUserId, request));
    }

    /**
     * Sent once when playback ends — closing the player, scrolling away, navigating out — not
     * per progress tick: this is one row of training data per session, and a per-second ping
     * would flood the topic with rows describing the same session.
     *
     * <p>Separate from /view rather than a field on it because the two fire at different moments:
     * /view when playback starts, this when it ends and the played time is known.
     */
    @PostMapping("/watch")
    public ApiResponse<WatchResponse> recordWatch(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @Valid @RequestBody WatchRequest request) {
        return ApiResponse.success(viewService.recordWatch(videoId, currentUserId, request));
    }
}
