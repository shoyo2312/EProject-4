package com.tiktok.interactionservice.service;

import org.springframework.data.cassandra.core.query.CassandraPageRequest;

import java.nio.ByteBuffer;
import java.util.Base64;
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

    public static String encode(CassandraPageRequest pageRequest) {
        ByteBuffer pagingState = pageRequest.getPagingState();
        byte[] bytes = new byte[pagingState.remaining()];
        pagingState.duplicate().get(bytes);
        return Base64.getUrlEncoder().encodeToString(bytes);
    }
}
