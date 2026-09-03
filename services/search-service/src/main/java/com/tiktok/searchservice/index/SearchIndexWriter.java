package com.tiktok.searchservice.index;

import com.tiktok.searchservice.document.ProductDocument;
import com.tiktok.searchservice.document.VideoDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.query.ScriptType;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every write into the two search indexes, expressed as a partial update rather than a
 * read-modify-write.
 *
 * <p>The consumers used to do {@code findById} → mutate → {@code save()}, which re-indexes the
 * whole document from a copy read some milliseconds earlier. Two effects, both silent: a like and
 * a share landing together lose one of the two increments, and a counter consumer that read the
 * document while it still said {@code PROCESSING} writes that status back over a transcode result
 * that arrived in between — dropping a published video out of search for good. A scripted update
 * touches only the fields it names and is applied on the shard against the current document, so
 * neither can happen; {@code retryOnConflict} covers two scripts hitting the same document at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexWriter {

    /** Matches the {@code date_hour_minute_second_millis} mapping on the date fields. */
    private static final DateTimeFormatter ES_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private static final int RETRY_ON_CONFLICT = 3;
    private static final int NOT_FOUND = 404;

    /**
     * The publication carries the content; it must not overwrite a transcode outcome that got
     * here first. Status is only assigned when the document has none — and then from
     * {@code pendingStatus}, which is where an early transcode parked its result.
     */
    private static final String PUBLISH_SCRIPT = """
            ctx._source.userId = params.userId;
            ctx._source.title = params.title;
            ctx._source.description = params.containsKey('description') ? params.description : null;
            ctx._source.tags = params.tags;
            ctx._source.createdAt = params.createdAt;
            if (ctx._source.status == null) {
              ctx._source.status = ctx._source.pendingStatus != null ? ctx._source.pendingStatus : 'PROCESSING';
            }
            if (ctx._source.viewCount == null) { ctx._source.viewCount = 0; }
            if (ctx._source.likeCount == null) { ctx._source.likeCount = 0; }
            if (ctx._source.commentCount == null) { ctx._source.commentCount = 0; }
            if (ctx._source.shareCount == null) { ctx._source.shareCount = 0; }
            """;

    /**
     * The mirror image: a transcode result that beats its publication here creates a stub holding
     * {@code pendingStatus} and nothing else. The stub is deliberately not given a {@code status},
     * so it cannot surface in a search that has no title to show — but the outcome is on disk, and
     * the publication picks it up when it lands. Two topics with no ordering between them make
     * that arrival order routine, and it is guaranteed on a cold start replaying both from
     * {@code earliest}.
     */
    private static final String TRANSCODE_SCRIPT = """
            ctx._source.pendingStatus = params.status;
            if (ctx._source.status != null) { ctx._source.status = params.status; }
            if (params.containsKey('thumbnailUrl')) { ctx._source.thumbnailUrl = params.thumbnailUrl; }
            if (params.containsKey('durationSeconds')) { ctx._source.durationSeconds = params.durationSeconds; }
            """;

    private static final String COUNTER_SCRIPT = """
            long current = ctx._source[params.field] == null ? 0L : ((Number) ctx._source[params.field]).longValue();
            long next = current + params.delta;
            ctx._source[params.field] = next < 0 ? 0 : next;
            """;

    /** A restore puts back what moderation interrupted, not a blanket PUBLISHED. */
    private static final String RESTORE_SCRIPT = """
            ctx._source.status = ctx._source.pendingStatus != null ? ctx._source.pendingStatus : 'PUBLISHED';
            """;

    private static final String STATUS_SCRIPT = "ctx._source.status = params.status;";

    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * The Elasticsearch client cannot serialise a null inside a script's params, and a null in a
     * document's source is the same thing as the field not being there — so absent is the
     * encoding for null on both sides, and the scripts read nullable fields with
     * {@code containsKey}.
     */
    private static Map<String, Object> withoutNulls(Map<String, Object> values) {
        Map<String, Object> kept = new HashMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                kept.put(key, value);
            }
        });
        return kept;
    }

    public void indexPublication(String videoId, Long userId, String title, String description,
                                 List<String> tags, Instant createdAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("title", title);
        params.put("description", description);
        params.put("tags", tags == null ? List.of() : tags);
        params.put("createdAt", ES_DATE.format(createdAt));

        Map<String, Object> upsert = new HashMap<>(params);
        upsert.put("status", "PROCESSING");
        upsert.put("viewCount", 0);
        upsert.put("likeCount", 0);
        upsert.put("commentCount", 0);
        upsert.put("shareCount", 0);

        update(VideoDocument.class, videoId, PUBLISH_SCRIPT, withoutNulls(params), Document.from(withoutNulls(upsert)));
    }

    public void applyTranscode(String videoId, String status, String thumbnailUrl, Integer durationSeconds) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", status);
        params.put("thumbnailUrl", thumbnailUrl);
        params.put("durationSeconds", durationSeconds);

        Map<String, Object> upsert = new HashMap<>();
        upsert.put("pendingStatus", status);
        if (thumbnailUrl != null) {
            upsert.put("thumbnailUrl", thumbnailUrl);
        }
        if (durationSeconds != null) {
            upsert.put("durationSeconds", durationSeconds);
        }

        update(VideoDocument.class, videoId, TRANSCODE_SCRIPT, withoutNulls(params), Document.from(withoutNulls(upsert)));
    }

    /**
     * No upsert: an engagement event for a video this index has never heard of is a stale client
     * or a video that was deleted, and a counter with nothing to count belongs nowhere. Same
     * call the sibling consumer in video-service makes, and warned for the same reason.
     */
    public void applyCounterDelta(String videoId, String field, long delta) {
        update(VideoDocument.class, videoId, COUNTER_SCRIPT,
                Map.of("field", field, "delta", delta), null);
    }

    public void applyVideoStatus(String videoId, String status) {
        update(VideoDocument.class, videoId, STATUS_SCRIPT, Map.of("status", status), null);
    }

    public void restoreVideo(String videoId) {
        update(VideoDocument.class, videoId, RESTORE_SCRIPT, Map.of(), null);
    }

    public void applyProductStatus(Long productId, String status) {
        update(ProductDocument.class, String.valueOf(productId), STATUS_SCRIPT,
                Map.of("status", status), null);
    }

    public void deleteVideo(String videoId) {
        elasticsearchOperations.delete(videoId, VideoDocument.class);
    }

    private void update(Class<?> type, String id, String script, Map<String, Object> params, Document upsert) {
        UpdateQuery.Builder builder = UpdateQuery.builder(id)
                .withScriptType(ScriptType.INLINE)
                .withScript(script)
                .withParams(params)
                .withRetryOnConflict(RETRY_ON_CONFLICT);
        if (upsert != null) {
            builder.withUpsert(upsert);
        }

        try {
            elasticsearchOperations.update(builder.build(), elasticsearchOperations.getIndexCoordinatesFor(type));
        } catch (DataAccessException ex) {
            if (upsert == null && ElasticsearchStatus.is(ex, NOT_FOUND)) {
                log.warn("Update skipped, no {} document with id={}", type.getSimpleName(), id);
                return;
            }
            throw ex;
        }
    }
}
