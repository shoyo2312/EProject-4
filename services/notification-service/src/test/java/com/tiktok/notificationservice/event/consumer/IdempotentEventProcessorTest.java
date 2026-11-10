package com.tiktok.notificationservice.event.consumer;

import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Work that fails releases its claim so the redelivery can run it. The release goes to Mongo, which
 * is usually what just failed the work, so it can fail too, and that must not cost the event.
 */
@ExtendWith(MockitoExtension.class)
class IdempotentEventProcessorTest {

    private static final String EVENT_ID = "evt-1";
    private static final String TYPE = "UserRegisteredEvent";

    private final RuntimeException workFailure = new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
    private final RuntimeException releaseFailure = new DataAccessResourceFailureException("Mongo unreachable");
    private final Runnable failingWork = () -> {
        throw workFailure;
    };
    private final AtomicInteger runs = new AtomicInteger();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private IdempotentEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new IdempotentEventProcessor(processedEventRepository);
    }

    /** kafka-lib waits out an outage or parks the record on the DLT by the type of what it catches. */
    @Test
    void aFailedRelease_doesNotReplaceTheWorksFailure() {
        when(processedEventRepository.tryClaim(EVENT_ID, TYPE)).thenReturn(true);
        doThrow(releaseFailure).when(processedEventRepository).releaseClaim(EVENT_ID);

        assertThatThrownBy(() -> processor.runOnce(EVENT_ID, TYPE, failingWork))
                .isSameAs(workFailure)
                .hasSuppressedException(releaseFailure);
    }

    /** The claim the release left behind is this instance's own, not a sign the work was done. */
    @Test
    void aClaimLeftByAFailedRelease_isRunOnRedelivery() {
        when(processedEventRepository.tryClaim(EVENT_ID, TYPE)).thenReturn(true, false);
        doThrow(releaseFailure).when(processedEventRepository).releaseClaim(EVENT_ID);
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void aDuplicateOfAnEventThatNeverFailedHere_isSkipped() {
        when(processedEventRepository.tryClaim(EVENT_ID, TYPE)).thenReturn(false);

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }

    /**
     * The release had gone through after all, so the redelivery claims afresh. Once it succeeds the
     * event is done, and the next delivery is a duplicate like any other.
     */
    @Test
    void afterARedeliverySucceeds_theNextOneIsSkipped() {
        when(processedEventRepository.tryClaim(EVENT_ID, TYPE)).thenReturn(true, true, false);
        doThrow(releaseFailure).when(processedEventRepository).releaseClaim(EVENT_ID);
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);
        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    /** Once a release succeeds, a claim found later was taken by someone else, who ran the work. */
    @Test
    void afterALaterReleaseSucceeds_aClaimIsNoLongerTakenForThisInstancesOwn() {
        when(processedEventRepository.tryClaim(EVENT_ID, TYPE)).thenReturn(true, false, false);
        doThrow(releaseFailure).doNothing().when(processedEventRepository).releaseClaim(EVENT_ID);
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));
        catchThrowable(() -> processor.runOnce(EVENT_ID, TYPE, failingWork));

        processor.runOnce(EVENT_ID, TYPE, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }
}
