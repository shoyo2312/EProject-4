package com.tiktok.interactionservice.dto.request;

import com.tiktok.interactionservice.entity.CommentStatus;

import java.time.Instant;

/**
 * What TikTok Studio's comment inbox is narrowed to. Every field is optional; all of them null is the
 * whole inbox.
 *
 * @param videoId only comments on this video
 * @param status  only visible comments, or only the ones held for the owner's review
 * @param q       only comments containing this text, ignoring case and Vietnamese diacritics
 * @param from    posted at or after this instant
 * @param to      posted before this instant
 */
public record CommentInboxFilter(Long videoId, CommentStatus status, String q, Instant from, Instant to) {

    public static final CommentInboxFilter NONE = new CommentInboxFilter(null, null, null, null, null);
}
