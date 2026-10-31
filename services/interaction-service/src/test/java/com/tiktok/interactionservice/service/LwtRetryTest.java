package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.servererrors.DefaultWriteType;
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.data.cassandra.core.cql.CassandraExceptionTranslator;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LwtRetryTest {

    /**
     * What a repository call actually throws: Spring Data translates the driver's timeout before it
     * reaches the service. A mocked driver exception here once hid that the retry caught nothing.
     */
    private final RuntimeException timeout = new CassandraExceptionTranslator().translateExceptionIfPossible(
            new WriteTimeoutException(mock(Node.class), DefaultConsistencyLevel.SERIAL, 0, 1, DefaultWriteType.CAS));

    /** The timed-out attempt applied; the retry sees its row and says "not applied". */
    @Test
    void aRetryThatFindsItsOwnRowCountsAsApplied() {
        assertThat(LwtRetry.run(timesOutThen(false), () -> true, "Like")).isTrue();
    }

    @Test
    void aRetryThatFindsSomeoneElsesRowDoesNot() {
        assertThat(LwtRetry.run(timesOutThen(false), () -> false, "Like")).isFalse();
    }

    /** No timeout, no doubt: an ordinary duplicate like is not re-examined. */
    @Test
    void withoutATimeoutNotAppliedIsBelieved() {
        assertThat(LwtRetry.run(() -> false, () -> {
            throw new AssertionError("asked without a timeout");
        }, "Like")).isFalse();
    }

    @Test
    void everyAttemptTimingOutIsAConflict() {
        assertThatThrownBy(() -> LwtRetry.run(() -> {
            throw timeout;
        }, () -> true, "Like"))
                .isInstanceOf(InteractionConflictException.class)
                .hasCause(timeout);
    }

    private BooleanSupplier timesOutThen(boolean applied) {
        AtomicInteger calls = new AtomicInteger();
        return () -> {
            if (calls.getAndIncrement() == 0) {
                throw timeout;
            }
            return applied;
        };
    }
}
