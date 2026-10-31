package com.tiktok.searchservice.event.consumer;

import com.tiktok.searchservice.document.ProcessedEventDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Work that fails deletes its claim so the redelivery can run it. The delete goes to the same
 * Elasticsearch that usually just failed the work — a restart, a 429 — so it can fail too, and that
 * must not cost the event: here a takedown or a verdict.
 */
@ExtendWith(MockitoExtension.class)
class IdempotentEventProcessorTest {

    private static final String EVENT_ID = "evt-1";
    private static final String TYPE = "VideoTakenDownEvent";

    /** What a create for an id that already exists comes back as; see ElasticsearchStatus. */
    private static final UncategorizedElasticsearchException ALREADY_CLAIMED =
            new UncategorizedElasticsearchException("version_conflict_engine_exception", 409, "{}", null);

    /** SearchIndexWriter's 429, already classified as transient so kafka-lib waits it out. */
    private final RuntimeException workFailure =
            new TransientDataAccessResourceException("Elasticsearch answered 429, retrying until it serves again");
    /** The delete's 429 is not classified, so on its own kafka-lib would give it three tries and the DLT. */
    private final RuntimeException releaseFailure =
            new UncategorizedElasticsearchException("es_rejected_execution_exception", 429, "{}", null);
    private final Runnable failingWork = () -> {
        throw workFailure;
    };
    private final AtomicInteger runs = new AtomicInteger();

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    private IdempotentEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new IdempotentEventProcessor(elasticsearchOperations);
    }

    /** kafka-lib waits out an outage or parks the record on the DLT by the type of what it catches. */
    @Test
    void aFailedRelease_doesNotReplaceTheWorksFailure() {
        when(elasticsearchOperations.index(any(IndexQuery.class), any())).thenReturn(EVENT_ID);
        when(elasticsearchOperations.delete(EVENT_ID, ProcessedEventDocument.class)).thenThrow(releaseFailure);

        assertThatThrownBy(() -> processor.runOnce(EVENT_ID, TYPE, failingWork))
                .isSameAs(workFailure)
                .hasSuppressedException(releaseFailure);
    }

    /** The claim the release left behind is this instance's own, not a sign the work was done. */
    @Test
    void aClaimLeftByAFailedRelease_isRunOnRedelivery() {
        when(elasticsearchOperations.index(any(IndexQuery.class), any()))
                .thenReturn(EVENT_ID)
                .thenThrow(ALREADY_CLAIMED);
        when(elasticsearchOperations.delete(EVENT_ID, ProcessedEventDocument.class)).thenThrow(releaseFailure);
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void aDuplicateOfAnEventThatNeverFailedHere_isSkipped() {
        when(elasticsearchOperations.index(any(IndexQuery.class), any())).thenThrow(ALREADY_CLAIMED);

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }

    /**
     * The release had gone through after all, so the redelivery claims afresh. Once it succeeds the
     * event is done, and the next delivery is a duplicate like any other.
     */
    @Test
    void afterARedeliverySucceeds_theNextOneIsSkipped() {
        when(elasticsearchOperations.index(any(IndexQuery.class), any()))
                .thenReturn(EVENT_ID, EVENT_ID)
                .thenThrow(ALREADY_CLAIMED);
        when(elasticsearchOperations.delete(EVENT_ID, ProcessedEventDocument.class)).thenThrow(releaseFailure);
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);
        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    /** Once a release succeeds, a claim found later was taken by someone else, who ran the work. */
    @Test
    void afterALaterReleaseSucceeds_aClaimIsNoLongerTakenForThisInstancesOwn() {
        when(elasticsearchOperations.index(any(IndexQuery.class), any()))
                .thenReturn(EVENT_ID)
                .thenThrow(ALREADY_CLAIMED);
        when(elasticsearchOperations.delete(EVENT_ID, ProcessedEventDocument.class))
                .thenThrow(releaseFailure)
                .thenReturn(EVENT_ID);
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }
}
