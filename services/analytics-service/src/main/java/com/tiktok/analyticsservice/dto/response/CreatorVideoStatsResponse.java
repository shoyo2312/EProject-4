package com.tiktok.analyticsservice.dto.response;

/**
 * One of a creator's videos over a window. Ids only: the client hydrates titles and thumbnails from
 * video-service's {@code /videos/batch}, the way the admin console's top list does.
 */
public record CreatorVideoStatsResponse(
        String videoId,
        long views,
        long viewers,
        long likes,
        long comments,
        long shares
) {
}
