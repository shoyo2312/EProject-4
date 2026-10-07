package com.tiktok.authservice.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UsernameCooldownTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void aHandleNeverChanged_mayChangeNow() {
        assertThat(UsernameCooldown.changeableAt(null, NOW)).isNull();
    }

    @Test
    void aRecentChange_opensThirtyDaysAfterIt() {
        Instant changedAt = NOW.minus(Duration.ofDays(29));

        assertThat(UsernameCooldown.changeableAt(changedAt, NOW)).isEqualTo(changedAt.plus(Duration.ofDays(30)));
    }

    @Test
    void exactlyThirtyDaysLater_itIsOpen() {
        assertThat(UsernameCooldown.changeableAt(NOW.minus(Duration.ofDays(30)), NOW)).isNull();
    }
}
