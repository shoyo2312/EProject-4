package com.tiktok.searchservice.index;

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
 * Every write into the video search index, expressed as a partial update rather than a
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
            ctx._source.visibility = params.containsKey('visibility') ? params.visibility : 'PUBLIC';
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
     * A transcode result, mirroring {@code Video.markTranscoded} in video-service: it only applies
     * to a video still waiting for one. FAILED stays open, so a redelivered publish that succeeds
     * can still rescue the upload; anything past that already has its result, and a second one is
     * a duplicate. The media fields are written either way — they describe the file.
     *
     * <p>A success lands on PENDING_MODERATION unless a verdict got here first, in which case the
     * verdict is the outcome. The two come on separate topics, so that order is routine: a
     * consumer restarting with both waiting, or a cold start replaying both from
     * {@code earliest}. This used to write PENDING_MODERATION unconditionally, which dropped a
     * video the verdict had already published back out of search for good.
     *
     * <p>A result that beats its publication creates a stub holding {@code pendingStatus} and
     * nothing else. The stub is deliberately not given a {@code status}, so it cannot surface in a
     * search that has no title to show; the publication picks the outcome up when it lands.
     *
     * <p>TAKEN_DOWN is never overwritten, mirroring {@code Video.applyOutcome}: a moderator acting
     * on a video still in the pipeline is routinely overtaken by it, and the outcome belongs in
     * {@code pendingStatus} for the restore to read, not in {@code status} where it would put the
     * video back into search results.
     */
    private static final String TRANSCODE_SCRIPT = """
            def reached = ctx._source.pendingStatus;
            if (reached == null || reached == 'PROCESSING' || reached == 'FAILED') {
              def outcome = !params.success ? 'FAILED'
                  : (ctx._source.verdict != null ? ctx._source.verdict : 'PENDING_MODERATION');
              ctx._source.pendingStatus = outcome;
              if (ctx._source.status != null && ctx._source.status != 'TAKEN_DOWN') {
                ctx._source.status = outcome;
              }
            }
            if (params.containsKey('thumbnailUrl')) { ctx._source.thumbnailUrl = params.thumbnailUrl; }
            if (params.containsKey('durationSeconds')) { ctx._source.durationSeconds = params.durationSeconds; }
            """;

    /**
     * A moderation verdict, mirroring {@code Video.applyModeration}. Only the first one counts,
     * which is what {@code verdict} records: a second is a duplicate event, and applying it would
     * overrule the first or an admin's approval since — a replay after an approval used to put the
     * video back under review here.
     *
     * <p>It moves the status only from PENDING_MODERATION. Before the transcode it is kept for
     * {@link #TRANSCODE_SCRIPT} and changes nothing a viewer sees: a video with no media yet is
     * not playable, and video-service does not publish it either.
     */
    private static final String VERDICT_SCRIPT = """
            if (ctx._source.verdict != null) {
              ctx.op = 'noop';
            } else {
              ctx._source.verdict = params.status;
              if (ctx._source.pendingStatus == 'PENDING_MODERATION') {
                ctx._source.pendingStatus = params.status;
                if (ctx._source.status != null && ctx._source.status != 'TAKEN_DOWN') {
                  ctx._source.status = params.status;
                }
              }
            }
            """;

    private static final String COUNTER_SCRIPT = """
            long current = ctx._source[params.field] == null ? 0L : ((Number) ctx._source[params.field]).longValue();
            long next = current + params.delta;
            ctx._source[params.field] = next < 0 ? 0 : next;
            """;

    /**
     * One admin action, two meanings, split the way {@code Video.markRestored} in video-service
     * splits them. On a taken-down video it lifts the takedown and puts back what the pipeline had
     * reached, not a blanket PUBLISHED. On a video that moderation held back (PENDING_REVIEW) or
     * rejected, it is the admin approving it, and the video goes live. That second case used to
     * read pendingStatus back too, which still held the verdict, so an approved video stayed out
     * of search for good.
     *
     * <p>pendingStatus moves to PUBLISHED with it, because a later takedown's restore reads it.
     * A stub the publication has not reached keeps a null status, as in TRANSCODE_SCRIPT.
     */
    private static final String RESTORE_SCRIPT = """
            if (ctx._source.status == 'TAKEN_DOWN') {
              ctx._source.status = ctx._source.pendingStatus != null ? ctx._source.pendingStatus : 'PUBLISHED';
            } else if (ctx._source.pendingStatus == 'PENDING_REVIEW' || ctx._source.pendingStatus == 'REJECTED') {
              ctx._source.pendingStatus = 'PUBLISHED';
              if (ctx._source.status != null) { ctx._source.status = 'PUBLISHED'; }
            } else {
              ctx.op = 'noop';
            }
            """;

    /**
     * The takedown half of {@link #RESTORE_SCRIPT}. A restore puts {@code pendingStatus} back, and
     * a video taken down before the pipeline recorded anything had none, so it came back as
     * PUBLISHED — searchable while still transcoding, and never screened. The status it had is
     * recorded instead; for a document indexed before {@code pendingStatus} existed that is the
     * PUBLISHED the fallback used to assume.
     */
    private static final String TAKEDOWN_SCRIPT = """
            if (ctx._source.pendingStatus == null && ctx._source.status != null && ctx._source.status != 'TAKEN_DOWN') {
              ctx._source.pendingStatus = ctx._source.status;
            }
            ctx._source.status = 'TAKEN_DOWN';
            """;

    private static final String VISIBILITY_SCRIPT = "ctx._source.visibility = params.visibility;";

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
                                 String visibility, List<String> tags, Instant createdAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);
        params.put("title", title);
        params.put("description", description);
        params.put("visibility", visibility);
        params.put("tags", tags == null ? List.of() : tags);
        params.put("createdAt", ES_DATE.format(createdAt));

        Map<String, Object> upsert = new HashMap<>(params);
        upsert.put("status", "PROCESSING");
        upsert.putIfAbsent("visibility", "PUBLIC");
        upsert.put("viewCount", 0);
        upsert.put("likeCount", 0);
        upsert.put("commentCount", 0);
        upsert.put("shareCount", 0);

        update(VideoDocument.class, videoId, PUBLISH_SCRIPT, withoutNulls(params), Document.from(withoutNulls(upsert)));
    }

    /**
     * A transcode result. A success is not PUBLISHED: video-service parks it at PENDING_MODERATION
     * and only the verdict moves it on — indexing it as PUBLISHED here made every upload
     * searchable before it had been screened, and left a rejected one searchable for good.
     */
    public void applyTranscode(String videoId, boolean success, String thumbnailUrl, Integer durationSeconds) {
        Map<String, Object> params = new HashMap<>();
        params.put("success", success);
        params.put("thumbnailUrl", thumbnailUrl);
        params.put("durationSeconds", durationSeconds);

        Map<String, Object> upsert = new HashMap<>();
        upsert.put("pendingStatus", success ? "PENDING_MODERATION" : "FAILED");
        upsert.put("thumbnailUrl", thumbnailUrl);
        upsert.put("durationSeconds", durationSeconds);

        update(VideoDocument.class, videoId, TRANSCODE_SCRIPT, withoutNulls(params), Document.from(withoutNulls(upsert)));
    }

    /** @param status the status the verdict maps to: PUBLISHED, PENDING_REVIEW or REJECTED */
    public void applyVerdict(String videoId, String status) {
        update(VideoDocument.class, videoId, VERDICT_SCRIPT, Map.of("status", status),
                Document.from(Map.of("verdict", status)));
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

    public void takeDownVideo(String videoId) {
        update(VideoDocument.class, videoId, TAKEDOWN_SCRIPT, Map.of(), null);
    }

    /**
     * No upsert, like the counters: a visibility change for a video this index has never heard of
     * is one whose publication has not arrived yet, and that event carries the current visibility
     * itself — creating a stub here would only race it.
     */
    public void applyVisibility(String videoId, String visibility) {
        update(VideoDocument.class, videoId, VISIBILITY_SCRIPT, Map.of("visibility", visibility), null);
    }

    public void restoreVideo(String videoId) {
        update(VideoDocument.class, videoId, RESTORE_SCRIPT, Map.of(), null);
    }

    public void deleteVideo(String videoId) {
        try {
            elasticsearchOperations.delete(videoId, VideoDocument.class);
        } catch (DataAccessException ex) {
            throw ElasticsearchStatus.classify(ex);
        }
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
            throw ElasticsearchStatus.classify(ex);
        }
    }
}
