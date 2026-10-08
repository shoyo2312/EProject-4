package com.tiktok.searchservice.index;

import co.elastic.clients.json.JsonData;
import com.tiktok.searchservice.document.OwnerBanDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who is banned, for the search query to leave out. A ban used to stop at auth-service, and a
 * banned account's videos stayed searchable.
 *
 * <p>Read at query time rather than stamped on the video documents, for two reasons. A ban carries
 * its own end and nothing announces it — auth-service's ExpiredBanSweep lifts a temporary ban
 * quietly — so a stamp would need something to clear it. And the ban and a video's publication
 * arrive on different topics: a stamp written across the owner's documents misses one whose
 * publication this consumer has not read yet.
 *
 * <p>ponytail: every search reads the bans in force and sends them as a terms filter. Past a few
 * thousand bans, stamp the ban on the documents (and clear it with a sweep) instead.
 */
@Component
@RequiredArgsConstructor
public class OwnerBans {

    /** A ban with no end. */
    public static final long PERMANENT = Long.MAX_VALUE;

    /** The result window: far past the bans this service will hold, and as many as one query returns. */
    private static final int MAX_BANS = 10_000;

    private static final int RETRY_ON_CONFLICT = 3;

    /** Assigned unless a newer decision is already there — a replay off the DLT arriving late. */
    private static final String RECORD_SCRIPT = """
            if (ctx._source.changedAt != null && ctx._source.changedAt > params.changedAt) {
              ctx.op = 'noop';
            } else {
              ctx._source.bannedUntil = params.containsKey('bannedUntil') ? params.bannedUntil : null;
              ctx._source.changedAt = params.changedAt;
            }
            """;

    private final ElasticsearchOperations elasticsearchOperations;

    /** @param bannedUntil epoch millis, {@link #PERMANENT}, or null for a ban lifted */
    public void record(Long userId, Long bannedUntil, Instant decidedAt) {
        // Absent rather than null, as in SearchIndexWriter: the client cannot send a null param.
        Map<String, Object> params = new HashMap<>();
        params.put("changedAt", decidedAt.toEpochMilli());
        if (bannedUntil != null) {
            params.put("bannedUntil", bannedUntil);
        }
        Map<String, Object> upsert = new HashMap<>(params);
        upsert.put("userId", userId);

        UpdateQuery query = UpdateQuery.builder(String.valueOf(userId))
                .withScriptType(ScriptType.INLINE)
                .withScript(RECORD_SCRIPT)
                .withParams(params)
                .withUpsert(Document.from(upsert))
                .withRetryOnConflict(RETRY_ON_CONFLICT)
                .build();
        try {
            elasticsearchOperations.update(query, elasticsearchOperations.getIndexCoordinatesFor(OwnerBanDocument.class));
        } catch (DataAccessException ex) {
            throw ElasticsearchStatus.classify(ex);
        }
    }

    /** The owners whose ban is in force now. */
    public List<Long> active() {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.range(r -> r.field("bannedUntil").gt(JsonData.of(System.currentTimeMillis()))))
                .withMaxResults(MAX_BANS)
                .build();
        return elasticsearchOperations.search(query, OwnerBanDocument.class).stream()
                .map(SearchHit::getContent)
                .map(OwnerBanDocument::getUserId)
                .toList();
    }
}
