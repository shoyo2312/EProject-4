package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.FeedItemResponse;

import java.util.List;

public interface FeedService {

    /**
     * A personalized ranking of video ids for one viewer. Falls back to plain trending when the
     * viewer has no history — a cold feed is still a feed, and refusing to answer is worse than
     * answering with what is popular.
     */
    List<FeedItemResponse> getFeed(Long userId, int limit);
}
