package com.tiktok.interactionservice.dto.response;

import java.util.List;

/**
 * One page of a thread as the moderation console reads it. Same cursor shape as
 * {@link CommentPageResponse} — Cassandra's paging state, base64'd — so a client that already
 * pages the public listing pages this one the same way.
 *
 * <p>Unlike the public page, {@code items} is never empty while {@code hasMore} is true: nothing
 * is filtered out after Cassandra cuts the page, so a page that came back full stays full.
 */
public record AdminCommentPageResponse(
        List<AdminCommentResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
