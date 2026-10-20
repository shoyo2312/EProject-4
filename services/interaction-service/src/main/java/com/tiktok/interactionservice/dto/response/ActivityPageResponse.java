package com.tiktok.interactionservice.dto.response;

import java.util.List;

/**
 * {@link VideoIdPageResponse} with each row's timestamp kept — what a "Download your data" archive
 * needs and the public listings have no use for. Same cursor encoding ({@code CassandraCursors}).
 */
public record ActivityPageResponse(
        List<ActivityRecordResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
