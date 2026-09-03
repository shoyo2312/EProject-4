package com.tiktok.mediaworker.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real ffprobe binary JAVE2 unpacks — no network, the input is a file URL.
 * The size/duration decision logic is unit-tested against a mocked VideoProbe elsewhere.
 */
class JaveVideoProbeTest {

    private final VideoProbe probe = new JaveVideoProbe();

    @Test
    void durationSeconds_readsTheLengthOfARealClip() throws Exception {
        Path fixture = Path.of(
                JaveVideoProbeTest.class.getResource("/fixtures/sample-3s.mp4").toURI());

        int seconds = probe.durationSeconds(fixture.toUri().toString());

        assertThat(seconds).isBetween(2, 4); // fixture is 3s
    }

    @Test
    void durationSeconds_throwsWhenTheUrlIsNotMedia() {
        assertThatThrownBy(() -> probe.durationSeconds("file:///no/such/file.mp4"))
                .isInstanceOf(IllegalStateException.class);
    }
}
