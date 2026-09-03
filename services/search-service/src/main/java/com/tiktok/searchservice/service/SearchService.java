package com.tiktok.searchservice.service;

import com.tiktok.searchservice.dto.response.ProductSearchResponse;
import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;

public interface SearchService {

    Page<VideoSearchResponse> searchVideos(String query, String hashtag, Pageable pageable);

    Page<ProductSearchResponse> searchProducts(String query, String category, BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable);
}
