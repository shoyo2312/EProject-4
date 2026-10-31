package com.tiktok.searchservice.index;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A host restart brings this service up beside Elasticsearch rather than after it, and depends_on
 * does not apply to containers the Docker daemon restarts. start() used to throw on the first
 * refused connection and take the whole context down with it.
 */
class IndexBootstrapAwaitTest {

    private static final Duration PAUSE = Duration.ofMillis(1);

    @Test
    void awaitElasticsearch_retriesWhileTheConnectionIsRefusedThenReturns() {
        AtomicInteger attempts = new AtomicInteger();

        IndexBootstrap.awaitElasticsearch(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new DataAccessResourceFailureException("Connection refused");
            }
        }, Duration.ofSeconds(5), PAUSE);

        assertThat(attempts).hasValue(3);
    }

    @Test
    void awaitElasticsearch_givesUpOnceTheDeadlinePassesSoARealOutageStillFailsTheStart() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> IndexBootstrap.awaitElasticsearch(() -> {
            attempts.incrementAndGet();
            throw new DataAccessResourceFailureException("Connection refused");
        }, Duration.ofMillis(20), PAUSE))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(attempts.get()).isGreaterThan(1);
    }

    @Test
    void awaitElasticsearch_doesNotRetryAFailureThatIsNotAnOutage() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> IndexBootstrap.awaitElasticsearch(() -> {
            attempts.incrementAndGet();
            throw new InvalidDataAccessResourceUsageException("bad request");
        }, Duration.ofSeconds(5), PAUSE))
                .isInstanceOf(InvalidDataAccessResourceUsageException.class);

        assertThat(attempts).hasValue(1);
    }
}
