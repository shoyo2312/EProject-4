package com.tiktok.interactionservice.dto.response;

import com.tiktok.interactionservice.service.CassandraCursors;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;

import java.util.List;
import java.util.function.Function;

/**
 * A page of video ids off one user's own partition — their likes or their saves. Ids only:
 * interaction-service stores no video metadata, so titles and thumbnails are video-service's to
 * serve, and inlining them here would mean this service reading another service's database.
 */
public record VideoIdPageResponse(
        List<Long> videoIds,
        String nextCursor,
        boolean hasMore
) {

    /**
     * Wraps one Cassandra slice. No dead-row filtering and so no page-scanning loop like the
     * comment listing needs: likes and saves are deleted outright, never tombstoned into a page
     * the reader has to skip past.
     */
    public static <T> VideoIdPageResponse from(Slice<T> slice, Function<T, Long> videoIdOf) {
        boolean hasMore = slice.hasNext();
        return new VideoIdPageResponse(
                slice.getContent().stream().map(videoIdOf).toList(),
                hasMore ? CassandraCursors.encode((CassandraPageRequest) slice.nextPageable()) : null,
                hasMore);
    }
}
