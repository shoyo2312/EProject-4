package com.tiktok.kafka.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What an outbox has not sent yet, as two gauges tagged {@code outbox}:
 * {@code outbox_pending_events} and {@code outbox_oldest_pending_age_seconds}.
 *
 * <p>The age is the one to alert on. A count cannot tell a busy outbox from a stuck one, and
 * {@link OutboxDispatcher} only sees the batches it is handed, so a poller that stopped draining —
 * a dead scheduler thread, a row that fails on every attempt, a broker that never acks — looks
 * exactly like a quiet one from there. Both values are read from the service's own storage on
 * every scrape, which is what keeps the age climbing while nothing drains it.
 *
 * <p>Declare one bean per outbox; Spring Boot binds every {@link MeterBinder} bean to the
 * registry. A query that throws — the database is down — reports NaN for that scrape instead of
 * failing it.
 *
 * @param outbox             tag value, unique within the service
 * @param pending            rows still waiting for a broker ack
 * @param oldestPendingSince when the oldest of them became due, or null when there are none
 */
public record OutboxBacklog(String outbox, LongSupplier pending, Supplier<Instant> oldestPendingSince)
        implements MeterBinder {

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.pending.events", () -> pending.getAsLong())
                .tag("outbox", outbox)
                .description("Outbox rows not yet acknowledged by the broker")
                .register(registry);
        Gauge.builder("outbox.oldest.pending.age", this::oldestPendingAgeSeconds)
                .tag("outbox", outbox)
                .baseUnit("seconds")
                .description("How long the oldest unacknowledged outbox row has been due, 0 when there is none")
                .register(registry);
    }

    private double oldestPendingAgeSeconds() {
        Instant since = oldestPendingSince.get();
        return since == null ? 0 : Math.max(0, Duration.between(since, Instant.now()).toMillis() / 1000.0);
    }
}
