package com.tiktok.storyservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.storyservice.dto.response.StoryAnnouncementResponse;
import com.tiktok.storyservice.dto.response.StoryCardResponse;
import com.tiktok.storyservice.service.StoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Service-to-service: notification-service asks here, with no signed-in user behind it, whether a
 * story is still live before it notifies followers. api-gateway denies this prefix;
 * {@code SecurityConfig} admits only the internal token, like user-service's {@code /users/internal/**}.
 */
@RestController
@RequestMapping("/api/v1/stories/internal")
@RequiredArgsConstructor
public class InternalStoryController {

    private final StoryService storyService;

    /** 404 STORY_NOT_FOUND unless the story is live and its owner is not hidden. */
    @GetMapping("/{storyId}/announcement")
    public ApiResponse<StoryAnnouncementResponse> announcement(@PathVariable String storyId) {
        return ApiResponse.success(storyService.announcement(storyId));
    }

    /** Absent ids are stories {@code viewerId} may not see (or that are gone); never a 404 per id. */
    @GetMapping("/viewable")
    public ApiResponse<List<StoryCardResponse>> viewable(@RequestParam Long viewerId, @RequestParam List<String> ids) {
        return ApiResponse.success(storyService.viewable(viewerId, ids));
    }
}
