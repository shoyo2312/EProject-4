package com.tiktok.chatservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Resolved for the viewer on each read. No playback URL or stored snapshot. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoCard(String videoId, String state, Long ownerId, String thumbnailUrl,
                        String title, Integer durationSeconds) {
    public static VideoCard unavailable(String id) {
        return new VideoCard(id, "UNAVAILABLE", null, null, null, null);
    }
    public static VideoCard unknown(String id) {
        return new VideoCard(id, "UNKNOWN", null, null, null, null);
    }
    public VideoCard minimal() {
        return new VideoCard("AVAILABLE".equals(state) ? videoId : null, state, null, thumbnailUrl, null, null);
    }
}
