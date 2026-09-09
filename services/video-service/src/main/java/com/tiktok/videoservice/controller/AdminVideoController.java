package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.service.AdminVideoDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Separate from {@link VideoController} because the access rule is the opposite one: every GET
 * under /api/v1/videos is public, and this path is the single exception, pinned to ROLE_ADMIN in
 * SecurityConfig. Keeping it in its own class means that exception is visible rather than being
 * one annotation buried among the public endpoints.
 */
@RestController
@RequestMapping("/api/v1/videos/admin")
@RequiredArgsConstructor
public class AdminVideoController {

    private final AdminVideoDirectory adminVideoDirectory;

    @GetMapping
    public ApiResponse<Page<VideoResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) VideoStatus status,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success(adminVideoDirectory.search(q, status, pageable));
    }
}
