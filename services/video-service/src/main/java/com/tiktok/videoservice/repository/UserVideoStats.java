package com.tiktok.videoservice.repository;

/**
 * One creator's totals, summed across their videos. Zeros for a user with nothing to sum —
 * an unknown userId is an empty shelf here, never an error, exactly as the listing treats it.
 */
public record UserVideoStats(
        long videoCount,
        long totalLikes,
        long totalViews
) {

    public static final UserVideoStats EMPTY = new UserVideoStats(0, 0, 0);
}
