package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.service.LikeService;
import com.tiktok.interactionservice.service.RepostService;
import com.tiktok.interactionservice.service.SaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's interaction history. Likes and saves are "me" only — they are private, and a
 * {userId} path would be an authorisation question this service has no answer for. Reposts are
 * the exception: reposting is a public act, shown on the reposter's profile to everyone, so that
 * listing takes a {userId} and needs no token.
 */
@RestController
@RequestMapping("/api/v1/interactions/users")
@RequiredArgsConstructor
public class UserInteractionController {

    /** Same ceiling and same reason as the comment listing: {@code size} is the driver's fetch size. */
    private static final int MAX_PAGE_SIZE = 50;

    private final LikeService likeService;
    private final SaveService saveService;
    private final RepostService repostService;

    @GetMapping("/me/likes")
    public ApiResponse<VideoIdPageResponse> listLikedVideos(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                likeService.listLikedVideos(currentUserId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    @GetMapping("/me/saves")
    public ApiResponse<VideoIdPageResponse> listSavedVideos(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                saveService.listSavedVideos(currentUserId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    /** Public: anyone opening a profile's Reposts tab sees the same list its owner does. */
    @GetMapping("/{userId}/reposts")
    public ApiResponse<VideoIdPageResponse> listRepostedVideos(
            @PathVariable Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                repostService.listReposts(userId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
}
