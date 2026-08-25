package com.tiktok.interactionservice.dto.response;

import java.util.List;

/**
 * A page of video ids off one user's own partition — their likes or their saves. Ids only:
 * interaction-service stores no video metadata, so titles and thumbnails are video-service's to
 * serve, and inlining them here would mean this service reading another service's database.
 *
 * <p>Built by {@code CassandraCursors.page}; the cursor encoding lives with the rest of the
 * paging code rather than in here, so a DTO stays a DTO.
 */
public record VideoIdPageResponse(
        List<Long> videoIds,
        String nextCursor,
        boolean hasMore
) {
}
