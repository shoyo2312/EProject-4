package com.tiktok.kafka.config;

import tools.jackson.core.JacksonException;
import io.micrometer.core.instrument.Metrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.concurrent.TimeoutException;

/**
 * Without this, a poison message (bad JSON, or any exception thrown from a @KafkaListener) has
 * no configured recovery: Spring Kafka's default behavior retries it forever on the same
 * partition, blocking every message behind it. Services can override by declaring their own
 * DefaultErrorHandler bean.
 *
 * <p>Two kinds of failure, two policies. A dependency that is down — Postgres, Mongo, Redis,
 * Elasticsearch, Cassandra, a broker — is retried until it is back: the record is fine and the
 * next attempt after the restart succeeds. Everything else is about the record, so it gets three
 * retries a second apart and then goes to "&lt;topic&gt;.DLT" instead of stalling the partition.
 * The DLT used to take both, and nothing reads a DLT, so an Elasticsearch restart a few seconds
 * long dropped every takedown and verdict that arrived during it — for good.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaListener.class)
public class KafkaConsumerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerAutoConfiguration.class);

    /** How deep a cause chain is read; a chain that loops back on itself stops here. */
    private static final int MAX_CAUSE_DEPTH = 20;

    /**
     * A dependency that is down: retried until it is back, never parked. Capped at 30 s so the gap
     * between two polls — this wait plus one attempt's own timeout, 30 s for a Hikari or Mongo
     * connection — stays well inside max.poll.interval.ms (5 min); past that the consumer is
     * dropped from its group and the partition rebalances for nothing.
     *
     * <p>ponytail: the partition waits for the dependency, so an outage is visible as consumer lag
     * rather than as lost events. A dependency that never comes back stalls it until someone looks.
     */
    static final BackOff UNTIL_RECOVERED = untilRecovered();

    /** Anything else: four attempts a second apart, then the DLT. */
    private static final BackOff POISON = new FixedBackOff(1000L, 3);

    /**
     * The one place a record's fate is decided, so the one place to say so. An outage logs a WARN
     * per attempt — at the 30 s cap, two lines a minute per stuck partition. A record parked on the
     * DLT is an ERROR and a count on kafka_consumer_dead_letters_total{topic}: nothing replays a DLT
     * on its own, so the event stays missing until someone acts on one of the two.
     */
    private static final RetryListener DEAD_LETTER_ALARM = new RetryListener() {
        @Override
        public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
            if (isOutage(ex)) {
                log.warn("{} failed on attempt {}: a dependency is down, retrying until it is back — {}",
                        coordinates(record), deliveryAttempt, rootCause(ex));
            }
        }

        @Override
        public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
            log.error("{} parked on {}.DLT; nothing replays it on its own", coordinates(record), record.topic(), ex);
            Metrics.counter("kafka.consumer.dead.letters", "topic", record.topic()).increment();
        }
    };

    /**
     * The DLT recoverer publishes through KafkaOperations, so this backs off entirely when a
     * service has spring-kafka on the classpath but no configured template — otherwise the
     * condition passes and the context fails on a missing dependency instead.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaOperations.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterRecoverer(kafkaOperations), POISON);
        errorHandler.addNotRetryableExceptions(JacksonException.class);
        // Consulted again whenever the exception changes type, so a record that outlasts an
        // outage and then fails on its own content still ends on the DLT.
        errorHandler.setBackOffFunction((record, ex) -> backOffFor(ex));
        errorHandler.setRetryListeners(DEAD_LETTER_ALARM);
        return errorHandler;
    }

    /**
     * Parks a record on "&lt;topic&gt;.DLT", same partition. Named here rather than left to the
     * recoverer's default, which Spring Kafka 4 changed to "&lt;topic&gt;-dlt": the log line, the
     * alert, make dlt-* and every record parked before the upgrade use ".DLT", and a silent rename
     * would split a topic's dead letters across two topics with only one of them being watched.
     */
    public static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaOperations<?, ?> kafkaOperations) {
        return new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    }

    /** Null keeps the handler's default — the poison-message backoff. */
    static BackOff backOffFor(Exception ex) {
        return isOutage(ex) ? UNTIL_RECOVERED : null;
    }

    /**
     * Whether the failure is a dependency being unreachable rather than anything about the record.
     * The whole cause chain is read: the listener adapter wraps what a listener threw, and what
     * Hikari or a driver reports sits a level or two below that.
     *
     * <p>Matched on Spring's translation of resource failures plus the JDK network exceptions under
     * them, so no driver has to be on this library's classpath. That includes the JDK HttpClient
     * behind every service-to-service RestClient (PooledRequestFactory): it times out with an
     * {@link HttpTimeoutException}, which is neither a {@link SocketException} nor a
     * {@link SocketTimeoutException}, and a slow downstream is as much an outage as a refused
     * connection. A client that reports "unavailable" some other way has it mapped to a
     * {@link TransientDataAccessException} where it is caught — search-service does this for
     * Elasticsearch's 429 and 503.
     */
    static boolean isOutage(Throwable ex) {
        Throwable t = ex;
        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++, t = t.getCause()) {
            if (t instanceof TransientDataAccessException           // timeouts, lock conflicts
                    || t instanceof DataAccessResourceFailureException  // connection refused or lost
                    || t instanceof RecoverableDataAccessException
                    || t instanceof CannotCreateTransactionException    // no connection at transaction begin
                    || t instanceof SQLTransientException               // Hikari: connection not available
                    || t instanceof SQLRecoverableException
                    || t instanceof SocketException                     // refused, reset, no route
                    || t instanceof SocketTimeoutException
                    || t instanceof HttpTimeoutException                // JDK HttpClient: connect or read timeout
                    || t instanceof UnknownHostException                // a container mid-restart
                    || t instanceof TimeoutException
                    || t instanceof RetriableException) {               // Kafka: broker away, request timed out
                return true;
            }
        }
        return false;
    }

    private static BackOff untilRecovered() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        return backOff;
    }

    private static String coordinates(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }

    private static String rootCause(Throwable ex) {
        Throwable root = ex;
        for (int depth = 0; root.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
            root = root.getCause();
        }
        return root.toString();
    }
}
