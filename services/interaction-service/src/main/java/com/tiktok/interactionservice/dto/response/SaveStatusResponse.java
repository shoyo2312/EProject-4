package com.tiktok.interactionservice.dto.response;

/**
 * Whether the calling user has this video in their favourites. No count alongside it, unlike
 * {@link LikeStatusResponse}: a save is private to the user, and video_counters has no
 * save_count column to read one from.
 */
public record SaveStatusResponse(
        Long videoId,
        boolean saved
) {
}
