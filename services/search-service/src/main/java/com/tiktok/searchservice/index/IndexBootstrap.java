package com.tiktok.searchservice.index;

import com.tiktok.searchservice.document.ProcessedEventDocument;
import com.tiktok.searchservice.document.VideoDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.reindex.ReindexRequest;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Creates the indexes with their declared mappings before anything writes to them.
 *
 * <p>Nothing else does it now that the writes go through scripted updates rather than
 * repositories — and an index Elasticsearch auto-creates on first write gets a dynamic mapping
 * instead of the declared one, which for {@code tags} means text-analysed rather than keyword.
 * That fails in the worst way available: no error, and a tag filter that starts matching
 * loosely.
 *
 * <p>A {@link SmartLifecycle} rather than an ApplicationRunner, and phased below the Kafka
 * listener containers on purpose: runners execute after the context has started, by which point
 * the consumers are already delivering. A scripted update or a delete landing in the window
 * between dropping the live index and recreating it auto-creates {@code videos} with a dynamic
 * mapping — the very failure this class exists to prevent — and the create that follows then
 * fails the startup outright. Starting here means no listener has been handed a record yet.
 *
 * <p>The videos index is also brought up to date when it predates the prefix sub-fields. Its
 * analysers live in the index settings, which cannot change on an open index, so the documents
 * are parked in a staging index while the live one is dropped and recreated with the current
 * mapping, then copied back. The staging index survives a crash midway, and the next start
 * finishes the job from it — including when the crash landed after live was recreated, which
 * looks like a finished migration and is not one. Staging is deleted only after a copy-back has
 * run in the same startup, because until then it holds the only full copy of the documents.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexBootstrap implements SmartLifecycle {

    private static final String STAGING_SUFFIX = "-migration";

    /**
     * Anything below {@link AbstractMessageListenerContainer#DEFAULT_PHASE} runs before the
     * listener containers; the margin leaves room for anything else that has to sit between.
     */
    private static final int PHASE = AbstractMessageListenerContainer.DEFAULT_PHASE - 1000;

    private final ElasticsearchOperations elasticsearchOperations;

    private volatile boolean running;

    @Override
    public void start() {
        createOrMigrateVideos();
        create(ProcessedEventDocument.class);
        running = true;
    }

    /** Nothing to unwind: the work is index creation, which is done by the time start() returns. */
    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private void create(Class<?> type) {
        IndexOperations indexOps = elasticsearchOperations.indexOps(type);
        if (indexOps.exists()) {
            return;
        }
        indexOps.createWithMapping();
        log.info("Created index for {}", type.getSimpleName());
    }

    private void createOrMigrateVideos() {
        IndexOperations live = elasticsearchOperations.indexOps(VideoDocument.class);
        IndexCoordinates liveIndex = live.getIndexCoordinates();
        IndexCoordinates stagingIndex = IndexCoordinates.of(liveIndex.getIndexName() + STAGING_SUFFIX);
        IndexOperations staging = elasticsearchOperations.indexOps(stagingIndex);

        if (!live.exists()) {
            live.createWithMapping();
            if (staging.exists()) {
                // An earlier migration dropped the live index and died before copying back.
                log.warn("Resuming interrupted migration of {} from {}", liveIndex.getIndexName(),
                        stagingIndex.getIndexName());
                restore(stagingIndex, liveIndex);
                staging.delete();
            } else {
                log.info("Created index for {}", VideoDocument.class.getSimpleName());
            }
            return;
        }

        if (hasPrefixSubfield(live.getMapping())) {
            if (staging.exists()) {
                // Live already carries the current mapping, so the migration got at least as far
                // as recreating it — but a leftover staging index means it never got to delete
                // it, and the copy-back in between is exactly what may not have run. The reindex
                // waits for completion, so a socket timeout on a large index leaves this state
                // with live half full. Dropping staging here, which is what this branch used to
                // do, would throw away the only complete copy. Copy first, delete after.
                log.warn("Finishing interrupted migration of {} from {}", liveIndex.getIndexName(),
                        stagingIndex.getIndexName());
                restore(stagingIndex, liveIndex);
                staging.delete();
            }
            return;
        }

        log.info("Migrating {} to the current mapping", liveIndex.getIndexName());
        if (staging.exists()) {
            // Left by an attempt that did not finish. Folded into rather than dropped: if that
            // attempt died between dropping the live index and recreating it, what stands as
            // "live" now is an auto-created index holding whatever arrived since, and staging is
            // still the only copy of everything before it. Live wins on the ids both hold, being
            // the one that has been written to since.
            log.warn("Reusing the staging index {} left behind by an earlier attempt",
                    stagingIndex.getIndexName());
        } else {
            staging.create(live.createSettings(), live.createMapping());
        }
        long copied = copy(liveIndex, stagingIndex);
        live.delete();
        live.createWithMapping();
        restore(stagingIndex, liveIndex);
        staging.delete();
        log.info("Migrated {} documents in {}", copied, liveIndex.getIndexName());
    }

    private long copy(IndexCoordinates from, IndexCoordinates to) {
        ReindexRequest request = ReindexRequest.builder(from, to).withRefresh(true).build();
        return elasticsearchOperations.reindex(request).getTotal();
    }

    /**
     * Copies the staging documents back into the live index, writing only the ids the live index
     * does not already hold.
     *
     * <p>{@code create} rather than a plain index write because this can run against a live index
     * that is already partly filled — a copy-back that was interrupted, or resumed on the next
     * start — and staging is a snapshot taken before the migration. Overwriting would replace a
     * document with the older copy. Conflicts are expected rather than exceptional here, hence
     * PROCEED: they are the documents that already made it.
     */
    private long restore(IndexCoordinates from, IndexCoordinates to) {
        ReindexRequest request = ReindexRequest.builder(from, to)
                .withDestOpType(IndexQuery.OpType.CREATE)
                .withConflicts(ReindexRequest.Conflicts.PROCEED)
                .withRefresh(true)
                .build();
        return elasticsearchOperations.reindex(request).getTotal();
    }

    /**
     * Whether the index was created with a mapping that has the prefix-analysed copy of the
     * title — the one piece the pre-migration mapping lacks.
     */
    static boolean hasPrefixSubfield(Map<String, Object> mapping) {
        Object properties = mapping.get("properties");
        if (!(properties instanceof Map<?, ?> props)) {
            return false;
        }
        Object title = props.get("title");
        if (!(title instanceof Map<?, ?> titleMapping)) {
            return false;
        }
        Object fields = titleMapping.get("fields");
        return fields instanceof Map<?, ?> subfields && subfields.containsKey(VideoDocument.PREFIX_SUBFIELD);
    }
}
