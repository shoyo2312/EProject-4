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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /**
     * @param ownerId repeatable: the ids a handle search resolved to elsewhere. This service
     *                stores the owner's id and user-service owns their handle, so "videos by
     *                @someone" can only arrive already resolved. Widens {@code q} — see
     *                {@link com.tiktok.videoservice.repository.VideoRepositoryCustom#findForAdmin}.
     */
    @GetMapping
    public ApiResponse<Page<VideoResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) VideoStatus status,
            @RequestParam(required = false) List<Long> ownerId,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success(adminVideoDirectory.search(q, status, ownerId, pageable));
    }

    /**
     * The moderation console reaching one video by id — from a report, which carries the id and
     * nothing else. The listing cannot stand in for this: it matches on title only, and it drops
     * videos their owner deleted.
     */
    @GetMapping("/{videoId}")
    public ApiResponse<VideoResponse> getById(@PathVariable String videoId) {
        return ApiResponse.success(adminVideoDirectory.getById(videoId));
    }
}
