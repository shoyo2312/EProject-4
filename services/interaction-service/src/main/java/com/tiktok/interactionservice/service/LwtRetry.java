package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.exception.InteractionConflictException;
import org.springframework.data.cassandra.CassandraWriteTimeoutException;

import java.util.function.BooleanSupplier;

/**
 * Runs a lightweight transaction, retrying it on {@link CassandraWriteTimeoutException}.
 *
 * <p>Spring's type, not the driver's {@code WriteTimeoutException}: repository calls go through
 * Spring Data, which translates the driver's exception before it gets here. Catching the driver
 * type caught nothing, so a timed-out like answered 500 and, if it had applied, never counted.
 *
 * <p>A timeout leaves the outcome unknown: the write may have applied before the coordinator gave
 * up waiting. Retrying IF NOT EXISTS / IF EXISTS is safe, but after an attempt that did apply the
 * retry answers "not applied" — the row it would insert is already there, the one it would delete
 * already gone. Taken at its word, that told the caller its own write never happened, so a like
 * was stored and its counter never moved. After a timeout, "not applied" is therefore checked
 * against {@code landedEarlier} before it is believed.
 */
final class LwtRetry {

    private static final int MAX_RETRIES = 2;

    private LwtRetry() {
    }

    /**
     * @param landedEarlier asked only when a retry after a timeout was not applied: whether the
     *                      row as it now stands is the one an earlier attempt of this call wrote
     * @param what          names the operation in the conflict raised when every attempt timed out
     */
    static boolean run(BooleanSupplier lwt, BooleanSupplier landedEarlier, String what) {
        CassandraWriteTimeoutException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return lwt.getAsBoolean() || (lastError != null && landedEarlier.getAsBoolean());
            } catch (CassandraWriteTimeoutException e) {
                lastError = e;
            }
        }
        // The last timeout is attached as the cause: what the coordinator said about the failed
        // write is the only thing that explains a conflict nobody can reproduce afterwards.
        InteractionConflictException failure = new InteractionConflictException(
                what + " could not be confirmed after retries, please try again");
        failure.initCause(lastError);
        throw failure;
    }

    /**
     * For an IF EXISTS delete: a retry that finds the row gone after a timeout takes the earlier
     * attempt to have deleted it.
     *
     * <p>ponytail: a concurrent delete of the same row by the same user, racing a write timeout,
     * is then counted by both. Closing that needs a per-request token on the row; it is far rarer
     * than the timeout-that-applied this fixes, which lost the count every time.
     */
    static boolean runDelete(BooleanSupplier lwt, String what) {
        return run(lwt, () -> true, what);
    }
}
