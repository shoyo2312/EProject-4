package com.tiktok.searchservice.index;

import com.tiktok.searchservice.document.VideoDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration's crash-recovery paths, against a real cluster.
 *
 * <p>What these are about: while the copy-back is in flight the staging index holds the only
 * complete copy of every video document, so the one thing the bootstrap must never do is delete
 * it on a start that has not copied from it. Both states below were produced by an earlier run
 * that died partway, and both used to end with staging dropped.
 */
@SpringBootTest
@Testcontainers
class IndexBootstrapMigrationTest {

    @Container
    @ServiceConnection
    static ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.14.0"))
            .withEnv("xpack.security.enabled", "false");

    private static final IndexCoordinates LIVE = IndexCoordinates.of("videos");
    private static final IndexCoordinates STAGING = IndexCoordinates.of("videos-migration");

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private IndexBootstrap indexBootstrap;

    @BeforeEach
    void freshCluster() {
        deleteIfPresent(LIVE);
        deleteIfPresent(STAGING);
    }

    /**
     * Live was already dropped and recreated with the current mapping, so the index looks
     * migrated — but staging is still there, which means the copy-back never finished. A reindex
     * over a non-trivial index that times out leaves exactly this, with live holding some of the
     * documents or none.
     */
    @Test
    void liveAlreadyMigratedButStagingLeftOver_copiesBackBeforeDroppingStaging() {
        elasticsearchOperations.indexOps(VideoDocument.class).createWithMapping();
        save("kept", "already copied", LIVE);
        indexOps(STAGING).create();
        save("kept", "already copied", STAGING);
        save("stranded", "never copied back", STAGING);

        indexBootstrap.start();

        assertThat(titleOf("stranded")).isEqualTo("never copied back");
        assertThat(titleOf("kept")).isEqualTo("already copied");
        assertThat(indexOps(STAGING).exists()).isFalse();
    }

    /**
     * A document already in live wins over the staging copy of it: staging is a snapshot taken
     * before the migration, so copying it over the top would undo whatever has been indexed
     * since.
     */
    @Test
    void copyBack_doesNotOverwriteADocumentLiveAlreadyHolds() {
        elasticsearchOperations.indexOps(VideoDocument.class).createWithMapping();
        save("v1", "the current title", LIVE);
        indexOps(STAGING).create();
        save("v1", "the stale snapshot", STAGING);

        indexBootstrap.start();

        assertThat(titleOf("v1")).isEqualTo("the current title");
    }

    /**
     * The compounding case: the previous run dropped live and died before recreating it, so a
     * write auto-created {@code videos} with a dynamic mapping. Live therefore reads as
     * unmigrated while staging still holds everything that was there before. Dropping staging to
     * start over — which is what this path used to do — loses every one of those documents.
     */
    @Test
    void legacyLiveWithLeftoverStaging_keepsBothSetsOfDocuments() {
        indexOps(LIVE).create(Map.of(), legacyMapping());
        save("arrived-after", "written while live was dynamic", LIVE);
        indexOps(STAGING).create();
        save("from-before", "parked by the failed run", STAGING);

        indexBootstrap.start();

        assertThat(titleOf("from-before")).isEqualTo("parked by the failed run");
        assertThat(titleOf("arrived-after")).isEqualTo("written while live was dynamic");
        assertThat(IndexBootstrap.hasPrefixSubfield(indexOps(LIVE).getMapping())).isTrue();
        assertThat(indexOps(STAGING).exists()).isFalse();
    }

    /** The ordinary start: nothing to migrate, nothing to resume. */
    @Test
    void liveAlreadyMigratedAndNoStaging_isLeftAlone() {
        elasticsearchOperations.indexOps(VideoDocument.class).createWithMapping();
        save("v1", "untouched", LIVE);

        indexBootstrap.start();

        assertThat(titleOf("v1")).isEqualTo("untouched");
        assertThat(indexOps(STAGING).exists()).isFalse();
    }

    private IndexOperations indexOps(IndexCoordinates index) {
        return elasticsearchOperations.indexOps(index);
    }

    /** The mapping as it was before the prefix sub-fields — what an old index still carries. */
    private static Document legacyMapping() {
        return Document.parse("""
                {"properties": {"title": {"type": "text"}, "description": {"type": "text"},
                 "tags": {"type": "keyword"}, "status": {"type": "keyword"}}}""");
    }

    private void save(String id, String title, IndexCoordinates index) {
        elasticsearchOperations.save(
                VideoDocument.builder().id(id).userId(1L).title(title).status("PUBLISHED").build(), index);
        indexOps(index).refresh();
    }

    private String titleOf(String id) {
        VideoDocument document = elasticsearchOperations.get(id, VideoDocument.class, LIVE);
        assertThat(document).as("document %s must survive the migration", id).isNotNull();
        return document.getTitle();
    }

    private void deleteIfPresent(IndexCoordinates index) {
        IndexOperations indexOps = indexOps(index);
        if (indexOps.exists()) {
            indexOps.delete();
        }
    }
}
