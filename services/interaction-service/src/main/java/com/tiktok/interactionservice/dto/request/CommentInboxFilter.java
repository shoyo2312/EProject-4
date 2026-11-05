package com.tiktok.interactionservice.dto.request;

import com.tiktok.interactionservice.entity.CommentStatus;

import java.time.Instant;
import java.util.Set;

/**
 * What TikTok Studio's comment inbox is narrowed to. Every field is optional; all of them null is the
 * whole inbox.
 *
 * @param videoId   only comments on this video
 * @param status    only visible comments, or only the ones held for the owner's review
 * @param q         only comments containing this text, ignoring case and Vietnamese diacritics
 * @param from      posted at or after this instant
 * @param to        posted before this instant
 * @param sort      the order; null is newest first
 * @param replied   true: only threads the owner has answered; false: only the ones they have not
 * @param followers only comments by accounts whose follower count falls in one of these ranges
 */
public record CommentInboxFilter(Long videoId, CommentStatus status, String q, Instant from, Instant to,
                                 Sort sort, Boolean replied, Set<FollowerRange> followers) {

    public static final CommentInboxFilter NONE = new CommentInboxFilter(null, null, null, null, null);

    public CommentInboxFilter(Long videoId, CommentStatus status, String q, Instant from, Instant to) {
        this(videoId, status, q, from, to, null, null, null);
    }

    public enum Sort { NEWEST, MOST_LIKED, MOST_REPLIED }

    /** Mirrors video-service's {@code StudioPostFilter.CountRange}, so the two Studio chips read alike. */
    public enum FollowerRange {
        UNDER_1K(0, 1_000),
        FROM_1K_TO_10K(1_000, 10_000),
        FROM_10K_TO_100K(10_000, 100_000),
        OVER_100K(100_000, Long.MAX_VALUE);

        public final long min;
        public final long max;

        FollowerRange(long min, long max) {
            this.min = min;
            this.max = max;
        }

        public boolean contains(long count) {
            return count >= min && count < max;
        }
    }

    /**
     * Whether the answer needs the whole video read and ranked in memory: an order other than the
     * table's own, or a filter on something only the thread or another service knows.
     */
    public boolean ranks() {
        return (sort != null && sort != Sort.NEWEST) || replied != null || (followers != null && !followers.isEmpty());
    }
}
