package com.tiktok.analyticsservice.dto.response;

/**
 * One row of the most-watched list.
 *
 * @param views      how many times it was watched over the window
 * @param watchedMs  total time spent on it — the ordering, because a 3-second bounce and a full
 *                   view are both one view, and a list ranked on views alone is a list of
 *                   thumbnails people clicked and left
 * @param completions how many of those watches reached the end
 * @param viewers    distinct accounts, so one person replaying a video cannot make it the top one
 */
public record TopVideoResponse(
        String videoId,
        long views,
        long watchedMs,
        long completions,
        long viewers
) {
}
