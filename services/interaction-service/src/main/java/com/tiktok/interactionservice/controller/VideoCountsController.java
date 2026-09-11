package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.InteractionCountResponse;
import com.tiktok.interactionservice.service.CounterCacheService;
import com.tiktok.interactionservice.service.VideoCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Counters for many videos in one request. It exists for the realtime fan-out in chat-service,
 * which learns from Kafka that a set of videos moved and then needs their current numbers — one
 * request per video would be one HTTP round trip per like.
 *
 * <p>Its own controller rather than a method on ShareController: that one is mapped under
 * {@code /videos/{videoId}}, where a literal {@code counts} segment would be swallowed by the
 * path variable.
 */
@RestController
@RequestMapping("/api/v1/interactions/videos")
@RequiredArgsConstructor
public class VideoCountsController {

    /**
     * Same ceiling as the like-status batch, and here for the same reason: every id costs a cache
     * read and possibly a Cassandra point read, and the ids arrive in a query string where
     * nothing else limits how many a caller may ask for. Distinct first, so a list padded with
     * one repeated id cannot use up the cap.
     */
    private static final int MAX_BATCH_SIZE = 50;

    private final CounterCacheService counterCacheService;

    @GetMapping("/counts/batch")
    public ApiResponse<List<InteractionCountResponse>> counts(@RequestParam List<Long> videoIds) {
        List<InteractionCountResponse> counts = videoIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_BATCH_SIZE)
                .map(videoId -> {
                    VideoCounts c = counterCacheService.getCounts(videoId);
                    return new InteractionCountResponse(videoId, c.likeCount(), c.commentCount(),
                            c.shareCount(), c.viewCount(), c.saveCount());
                })
                .toList();
        return ApiResponse.success(counts);
    }
}
