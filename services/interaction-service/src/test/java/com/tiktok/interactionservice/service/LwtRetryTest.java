package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LwtRetryTest {

    private final WriteTimeoutException timeout = mock(WriteTimeoutException.class);

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
