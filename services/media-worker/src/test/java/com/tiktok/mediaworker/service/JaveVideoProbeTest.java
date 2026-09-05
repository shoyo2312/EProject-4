package com.tiktok.mediaworker.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real ffmpeg binary JAVE2 unpacks — no network, the input is a file URL.
 * The decision logic built on top of the numbers read here is unit-tested in ProbedVideoTest.
 */
class JaveVideoProbeTest {

    private final VideoProbe probe = new JaveVideoProbe();

    @Test
    void probe_readsTheDurationCodecsAndSizeOfARealClip() throws Exception {
        Path fixture = Path.of(
                JaveVideoProbeTest.class.getResource("/fixtures/sample-3s.mp4").toURI());

        ProbedVideo probed = probe.probe(fixture.toUri().toString());

        assertThat(probed.durationSeconds()).isBetween(2, 4); // fixture is 3s
        // The decoder string ffmpeg prints carries a profile and a tag; only the codec survives.
        assertThat(probed.videoCodec()).isEqualTo("h264");
        assertThat(probed.width()).isPositive();
        assertThat(probed.height()).isPositive();
    }

    @Test
    void probe_throwsWhenTheUrlIsNotMedia() {
        assertThatThrownBy(() -> probe.probe("file:///no/such/file.mp4"))
                .isInstanceOf(IllegalStateException.class);
    }
}
