package com.tiktok.searchservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import com.tiktok.searchservice.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/videos")
    public ApiResponse<Page<VideoSearchResponse>> searchVideos(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String hashtag,
            Pageable pageable) {
        return ApiResponse.success(searchService.searchVideos(q, hashtag, pageable));
    }
}
