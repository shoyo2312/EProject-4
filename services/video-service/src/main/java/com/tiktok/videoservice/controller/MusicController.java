package com.tiktok.videoservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.dto.response.MusicTrackResponse;
import com.tiktok.videoservice.service.MusicService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The sounds the editor offers. Signed-in users only (SecurityConfig): every call spends the
 * catalogue quota of this service's one client id.
 */
@Validated
@RestController
@RequestMapping("/api/v1/videos/music")
@RequiredArgsConstructor
public class MusicController {

    private final MusicService musicService;

    /**
     * @param q    words to search titles, artists and tags for; absent for the most popular
     * @param page from 0, a few pages deep at most — a picker, not an archive
     */
    @GetMapping
    public ApiResponse<List<MusicTrackResponse>> search(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(20) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.success(musicService.search(q, page, size));
    }
}
