package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Cassandra's own paging state, base64'd for a query string. Shared by every cursor-paged listing
 * in this service so the encoding stays one thing: a cursor issued by one endpoint decodes the
 * same way everywhere, and only the exception for an unusable one differs per endpoint.
 */
public final class CassandraCursors {

    private CassandraCursors() {
    }

    public static CassandraPageRequest decode(String cursor, int size, Supplier<RuntimeException> onUnusable) {
        CassandraPageRequest firstPage = CassandraPageRequest.first(size);
        if (cursor == null || cursor.isBlank()) {
            return firstPage;
        }
        try {
            ByteBuffer pagingState = ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor));
            return CassandraPageRequest.of(firstPage, pagingState);
        } catch (IllegalArgumentException e) {
            // Not base64, or not paging state this driver recognises. Either way the client sent
            // something we never issued, and that is a 400 — not the 500 the catch-all would
            // otherwise report for what is a query-string typo.
            throw onUnusable.get();
        }
    }

    /**
     * Wraps one Cassandra slice of interaction rows as a page of video ids. No dead-row filtering
     * and so no page-scanning loop like the comment listing needs: likes and saves are deleted
     * outright, never tombstoned into a page the reader has to skip past.
     */
    public static <T> VideoIdPageResponse page(Slice<T> slice, Function<T, Long> videoIdOf) {
        boolean hasMore = slice.hasNext();
        return new VideoIdPageResponse(
                slice.getContent().stream().map(videoIdOf).toList(),
                hasMore ? encode((CassandraPageRequest) slice.nextPageable()) : null,
                hasMore);
    }

    public static String encode(CassandraPageRequest pageRequest) {
        ByteBuffer pagingState = pageRequest.getPagingState();
        byte[] bytes = new byte[pagingState.remaining()];
        pagingState.duplicate().get(bytes);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
}
