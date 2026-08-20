package com.tiktok.recommendationservice.dto.response;

import java.util.List;

/**
 * Ids and a score, not video documents. This service has no read path into video-service's
 * Mongo and must not grow one, so the client takes these ids and fetches the videos it does not
 * already hold. {@code reasons} is there so a bad ranking can be debugged from the response
 * instead of from Redis.
 */
public record FeedItemResponse(
        String videoId,
        double score,
        List<String> reasons
) {
}
