package com.tiktok.kafka.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBacklogTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    /** Read on every scrape, not once: a stalled poller has to show up as an age that keeps climbing. */
    @Test
    void reportsHowManyRowsWaitAndHowLongTheOldestHasBeenDue() {
        AtomicReference<Instant> oldest = new AtomicReference<>(Instant.now().minusSeconds(90));
        new OutboxBacklog("outbox_events", () -> 3, oldest::get).bindTo(registry);

        assertThat(gauge("outbox.pending.events")).isEqualTo(3);
        assertThat(gauge("outbox.oldest.pending.age")).isBetween(90.0, 91.0);

        oldest.set(Instant.now().minusSeconds(600));
        assertThat(gauge("outbox.oldest.pending.age")).isBetween(600.0, 601.0);
    }

    @Test
    void anEmptyOutboxIsZeroNotNaN() {
        new OutboxBacklog("outbox_events", () -> 0, () -> null).bindTo(registry);

        assertThat(gauge("outbox.oldest.pending.age")).isZero();
    }

    @Test
    void aFailingQueryReportsNaNInsteadOfFailingTheScrape() {
        new OutboxBacklog("outbox_events",
                () -> { throw new IllegalStateException("database down"); },
                () -> { throw new IllegalStateException("database down"); }).bindTo(registry);

        assertThat(gauge("outbox.pending.events")).isNaN();
        assertThat(gauge("outbox.oldest.pending.age")).isNaN();
    }

    private double gauge(String name) {
        return registry.get(name).tag("outbox", "outbox_events").gauge().value();
    }
}
