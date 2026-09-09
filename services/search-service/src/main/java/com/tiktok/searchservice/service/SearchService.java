package com.tiktok.searchservice.service;

import com.tiktok.searchservice.dto.response.VideoSearchResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SearchService {

    Page<VideoSearchResponse> searchVideos(String query, String hashtag, Pageable pageable);
}
