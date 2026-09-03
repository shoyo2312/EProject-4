package com.tiktok.searchservice.index;

import org.elasticsearch.client.ResponseException;
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException;

import java.util.OptionalInt;

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

    private ElasticsearchStatus() {
    }

    public static boolean is(Throwable failure, int status) {
        return of(failure).stream().anyMatch(actual -> actual == status);
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
