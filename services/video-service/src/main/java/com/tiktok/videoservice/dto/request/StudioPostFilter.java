package com.tiktok.videoservice.dto.request;

import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;

import java.util.List;

/**
 * TikTok Studio's Posts table: which of the owner's own videos, in what order. Every filter is
 * optional, and a list filter matches any one of its values; the owner sees every status, as on
 * their own profile grid.
 *
 * @param status     only videos in one of these statuses
 * @param visibility only videos with one of these audiences
 * @param q          case-insensitive substring of the title or the description
 * @param views      only videos whose view count falls in one of these ranges
 * @param likes      the same, for likes
 * @param comments   the same, for comments
 * @param sort       null for newest first
 * @param order      null for descending
 */
public record StudioPostFilter(
        List<VideoStatus> status,
        List<VideoVisibility> visibility,
        String q,
        List<CountRange> views,
        List<CountRange> likes,
        List<CountRange> comments,
        Sort sort,
        Order order) {

    public enum Sort {
        NEWEST, VIEWS, LIKES, COMMENTS
    }

    public enum Order {
        ASC, DESC
    }

    /** Studio's count buckets: "<1K", "1K–10K", "10K–100K", ">100K". Lower bound in, upper bound out. */
    public enum CountRange {
        UNDER_1K(0, 1_000),
        FROM_1K_TO_10K(1_000, 10_000),
        FROM_10K_TO_100K(10_000, 100_000),
        OVER_100K(100_000, Long.MAX_VALUE);

        public final long min;
        public final long max;

        CountRange(long min, long max) {
            this.min = min;
            this.max = max;
        }
    }
}
