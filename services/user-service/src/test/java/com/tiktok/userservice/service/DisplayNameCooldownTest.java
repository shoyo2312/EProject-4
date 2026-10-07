package com.tiktok.userservice.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayNameCooldownTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void aNameNeverChanged_mayChangeNow() {
        assertThat(DisplayNameCooldown.changeableAt(null, NOW)).isNull();
    }

    @Test
    void aRecentChange_opensSevenDaysAfterIt() {
        Instant changedAt = NOW.minus(Duration.ofDays(6));

        assertThat(DisplayNameCooldown.changeableAt(changedAt, NOW)).isEqualTo(changedAt.plus(Duration.ofDays(7)));
    }

    @Test
    void exactlySevenDaysLater_itIsOpen() {
        assertThat(DisplayNameCooldown.changeableAt(NOW.minus(Duration.ofDays(7)), NOW)).isNull();
    }
}
