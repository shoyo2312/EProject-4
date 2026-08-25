package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.SaveStatusResponse;
import com.tiktok.interactionservice.service.SaveService;
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
public class SaveController {

    private final SaveService saveService;

    @PostMapping("/save")
    public ApiResponse<SaveStatusResponse> save(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(saveService.save(videoId, currentUserId));
    }

    @DeleteMapping("/save")
    public ApiResponse<SaveStatusResponse> unsave(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(saveService.unsave(videoId, currentUserId));
    }

    /**
     * Authenticated, unlike like-status: a save is private, so there is no anonymous answer to
     * give and no count to show a logged-out viewer.
     */
    @GetMapping("/save-status")
    public ApiResponse<SaveStatusResponse> getStatus(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId) {
        return ApiResponse.success(saveService.getStatus(videoId, currentUserId));
    }
}
