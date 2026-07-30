package com.tiktok.searchservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.searchservice.dto.response.ProductSearchResponse;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import com.tiktok.searchservice.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping("/videos")
    public ApiResponse<Page<VideoSearchResponse>> searchVideos(
            @RequestParam(required = false) String q,
            Pageable pageable) {
        return ApiResponse.success(searchService.searchVideos(q, pageable));
    }

    @GetMapping("/products")
    public ApiResponse<Page<ProductSearchResponse>> searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            Pageable pageable) {
        return ApiResponse.success(searchService.searchProducts(q, category, minPrice, maxPrice, pageable));
    }
}
