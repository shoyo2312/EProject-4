package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.UpdateCommentSettingRequest;
import com.tiktok.videoservice.dto.request.UpdateVideoVisibilityRequest;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.UserVideoStatsResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.service.VideoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    /**
     * Call before {@link #publish}: upload the file to the returned {@code uploadUrl} with a plain
     * PUT, then send the returned {@code fileUrl} back as {@code rawFileUrl}.
     */
    @PostMapping("/upload-url")
    public ApiResponse<UploadUrlResponse> createUploadUrl(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody UploadUrlRequest request) {
        return ApiResponse.success(videoService.createUploadUrl(currentUserId, request));
    }

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

    /**
     * Hydrates a list of ids in one hop, in the order given — for callers that already hold a
     * ranking and only need the videos behind it (recommendation-service's feed returns ids and
     * no more). Literal path, so it is matched ahead of {@code /{videoId}} above.
     *
     * <p>Ids that resolve to nothing the caller may see are absent from the response rather than
     * failing it, so a feed naming a video deleted seconds ago still renders.
     */
    @GetMapping("/batch")
    public ApiResponse<List<VideoResponse>> getByIds(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam List<String> ids) {
        return ApiResponse.success(videoService.getByIds(currentUserId, ids));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<Page<VideoResponse>> listByUser(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId,
            Pageable pageable) {
        return ApiResponse.success(videoService.listByUser(currentUserId, userId, pageable));
    }

    /**
     * The profile header's totals. No token needed — the numbers a visitor sees are public;
     * sending one only widens the count to the owner's own hidden videos.
     */
    @GetMapping("/users/{userId}/stats")
    public ApiResponse<UserVideoStatsResponse> getUserStats(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(videoService.getUserStats(currentUserId, userId));
    }

    @DeleteMapping("/{videoId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId) {
        videoService.delete(currentUserId, videoId);
        return ApiResponse.success(null);
    }

    /**
     * The owner switching their own video between PUBLIC and PRIVATE from the detail page.
     * Owner-only, like {@link #delete}; a non-owner gets {@code NOT_VIDEO_OWNER}.
     */
    @PatchMapping("/{videoId}/visibility")
    public ApiResponse<VideoResponse> updateVisibility(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId,
            @Valid @RequestBody UpdateVideoVisibilityRequest request) {
        return ApiResponse.success(
                videoService.updateVisibility(currentUserId, videoId, request.visibility()));
    }

    /** Owner-only, like {@link #updateVisibility}. Turns new comments on or off for the video. */
    @PatchMapping("/{videoId}/comments-setting")
    public ApiResponse<VideoResponse> updateCommentsSetting(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId,
            @Valid @RequestBody UpdateCommentSettingRequest request) {
        return ApiResponse.success(
                videoService.updateCommentsDisabled(currentUserId, videoId, request.disabled()));
    }
}
