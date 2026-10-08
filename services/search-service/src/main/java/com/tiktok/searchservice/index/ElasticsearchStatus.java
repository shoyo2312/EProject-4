package com.tiktok.searchservice.index;

import org.elasticsearch.client.ResponseException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;

import java.util.OptionalInt;
import java.util.Set;

/**
 * The HTTP status behind a translated Elasticsearch failure.
 *
 * <p>Two of the statuses this service raises on purpose — 409 for a duplicate inbox claim, 404
 * for a counter on a video the index has never seen — and both have to be told apart from a
 * cluster that is simply down. Spring Data does not translate either into a distinct exception:
 * depending on how the transport reports it, the same 409 arrives as an
 * {@link UncategorizedElasticsearchException} carrying a status or as a
 * {@code DataAccessResourceFailureException} wrapping the transport's {@link ResponseException}.
 * So the status is read from whichever of the two it turned out to be, and a failure with no
 * status at all is not ours to interpret.
 */
public final class ElasticsearchStatus {

    /** What an Elasticsearch that is up but not serving answers with. */
    private static final Set<Integer> UNAVAILABLE = Set.of(429, 502, 503, 504);

    private ElasticsearchStatus() {
    }

    public static boolean is(Throwable failure, int status) {
        return of(failure).stream().anyMatch(actual -> actual == status);
    }

    /**
     * What a failed write is re-thrown as, so that kafka-lib's error handler reads it right: it
     * retries an outage until it ends, gives anything else three retries and the DLT, and tells
     * the two apart by exception type alone.
     *
     * <p>A failure with no status never got an answer — the connection — and already arrives as
     * an outage. One with a status did: 429 and 503 mean try again later (a cluster still
     * allocating shards after a restart answers 503 for a while), so they become transient. Any
     * other status is about the request, but a transport-level one arrives dressed as a resource
     * failure, which reads as an outage; it is re-raised with the response as its cause, so the
     * record goes to the DLT instead of stalling its partition for good.
     */
    public static RuntimeException classify(DataAccessException failure) {
        OptionalInt status = of(failure);
        if (status.isEmpty()) {
            return failure;
        }
        if (UNAVAILABLE.contains(status.getAsInt())) {
            return new TransientDataAccessResourceException(
                    "Elasticsearch answered " + status.getAsInt() + ", retrying until it serves again", failure);
        }
        return failure instanceof DataAccessResourceFailureException
                ? new InvalidDataAccessResourceUsageException(failure.getMessage(), failure.getCause())
                : failure;
    }

    private static OptionalInt of(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UncategorizedElasticsearchException translated
                    && translated.getStatusCode() != null) {
                return OptionalInt.of(translated.getStatusCode());
            }
            if (current instanceof ResponseException transport) {
                return OptionalInt.of(transport.getResponse().getStatusLine().getStatusCode());
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return OptionalInt.empty();
    }
}
