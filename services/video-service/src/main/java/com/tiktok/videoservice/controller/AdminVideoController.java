package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.dto.response.DailyVideoStatsResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.service.AdminVideoDirectory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Separate from {@link VideoController} because the access rule is the opposite one: every GET
 * under /api/v1/videos is public, and this path is the single exception, pinned to ROLE_ADMIN in
 * SecurityConfig. Keeping it in its own class means that exception is visible rather than being
 * one annotation buried among the public endpoints.
 */
@RestController
@RequestMapping("/api/v1/videos/admin")
@RequiredArgsConstructor
@Validated
public class AdminVideoController {

    private final AdminVideoDirectory adminVideoDirectory;

    /**
     * @param ownerId repeatable: the ids a handle search resolved to elsewhere. This service
     *                stores the owner's id and user-service owns their handle, so "videos by
     *                @someone" can only arrive already resolved. Widens {@code q} — see
     *                {@link com.tiktok.videoservice.repository.VideoRepositoryCustom#findForAdmin}.
     */
    /**
     * @param deleted null for no filter (the default — both live and deleted rows match);
     *                {@code true} for deleted rows only; {@code false} for live rows only
     * @param purged  the same three states on whether the media is erased — the console's trash
     *                is {@code deleted=true&purged=false}
     */
    @GetMapping
    public ApiResponse<Page<VideoResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) VideoStatus status,
            @RequestParam(required = false) List<Long> ownerId,
            @RequestParam(required = false) Boolean deleted,
            @RequestParam(required = false) Boolean purged,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success(adminVideoDirectory.search(q, status, ownerId, deleted, purged, pageable));
    }

    /**
     * Uploads per day — what the console's video growth percentages are computed from.
     *
     * <p>Bounded at three years because a year-over-year delta needs the year before the one on
     * screen; the lower bound keeps a hand-edited zero or a negative from asking Mongo for a
     * window in the future, which comes back empty and reads as lost data.
     */
    /**
     * Every status with how many videos the listing's filters match in it, zero included — the
     * console's status totals in one request instead of one listing request per status.
     */
    @GetMapping("/stats/by-status")
    public ApiResponse<Map<VideoStatus, Long>> countByStatus(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<Long> ownerId,
            @RequestParam(required = false) Boolean deleted,
            @RequestParam(required = false) Boolean purged) {
        return ApiResponse.success(adminVideoDirectory.countByStatus(q, ownerId, deleted, purged));
    }

    @GetMapping("/stats/daily")
    public ApiResponse<List<DailyVideoStatsResponse>> dailyStats(
            @RequestParam(defaultValue = "7") @Min(1) @Max(1095) int days) {
        return ApiResponse.success(adminVideoDirectory.dailyStats(days));
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
