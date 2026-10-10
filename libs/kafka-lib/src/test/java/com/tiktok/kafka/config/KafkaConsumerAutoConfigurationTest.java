package com.tiktok.kafka.config;

import tools.jackson.core.exc.StreamReadException;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOffExecution;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which failures park a record on the DLT. It used to be all of them after three retries a second
 * apart, so a dependency down for longer than that — an Elasticsearch restart, a Postgres failover
 * — sent every event that arrived meanwhile to a DLT nothing reads.
 */
class KafkaConsumerAutoConfigurationTest {

    /** What a listener throws is wrapped by the adapter, the way the error handler receives it. */
    private static Exception thrownByListener(Throwable cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }

    static Stream<Arguments> outages() {
        return Stream.of(
                Arguments.of("Postgres down at transaction begin", new CannotCreateTransactionException(
                        "Could not open JPA EntityManager for transaction",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available"))),
                Arguments.of("Mongo, Redis, Elasticsearch or Cassandra unreachable",
                        new DataAccessResourceFailureException("Connection refused")),
                Arguments.of("a command that timed out", new QueryTimeoutException("Redis command timed out")),
                Arguments.of("a driver that only reports the socket", new IllegalStateException(
                        "query failed", new ConnectException("Connection refused"))),
                Arguments.of("a container mid-restart", new UnknownHostException("elasticsearch")),
                Arguments.of("a broker that did not answer", new KafkaException("send failed",
                        new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s)"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("outages")
    void anOutage_isRetriedUntilTheDependencyIsBack(String what, Exception cause) {
        assertThat(KafkaConsumerAutoConfiguration.isOutage(thrownByListener(cause))).isTrue();
        assertThat(KafkaConsumerAutoConfiguration.backOffFor(thrownByListener(cause)))
                .isSameAs(KafkaConsumerAutoConfiguration.UNTIL_RECOVERED);
    }

    static Stream<Arguments> poison() {
        return Stream.of(
                Arguments.of("a bug", new NullPointerException("event.videoId()")),
                Arguments.of("a constraint the record breaks", new DataIntegrityViolationException("duplicate key")),
                // An IOException, but a statement about the bytes, not the network.
                Arguments.of("JSON that does not parse", new StreamReadException(null, "Unexpected character")),
                Arguments.of("an I/O failure that is not the network", new IOException("No space left on device")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("poison")
    void anythingElse_keepsTheBoundedRetriesAndThenTheDlt(String what, Exception cause) {
        assertThat(KafkaConsumerAutoConfiguration.isOutage(thrownByListener(cause))).isFalse();
        // Null is the handler's default backoff: three retries a second apart.
        assertThat(KafkaConsumerAutoConfiguration.backOffFor(thrownByListener(cause))).isNull();
    }

    @Test
    void untilRecovered_neverStops_andWaitsAtMostThirtySecondsBetweenAttempts() {
        BackOffExecution execution = KafkaConsumerAutoConfiguration.UNTIL_RECOVERED.start();

        long longest = 0;
        for (int attempt = 0; attempt < 1000; attempt++) {
            long wait = execution.nextBackOff();
            assertThat(wait).isNotEqualTo(BackOffExecution.STOP);
            longest = Math.max(longest, wait);
        }
        assertThat(longest).isEqualTo(30_000L);
    }

    @Test
    void aCauseChainThatLoops_isNotFollowedForever() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(KafkaConsumerAutoConfiguration.isOutage(first)).isFalse();
    }
}
