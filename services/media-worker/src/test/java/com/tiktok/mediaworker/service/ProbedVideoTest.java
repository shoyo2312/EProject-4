package com.tiktok.mediaworker.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProbedVideoTest {

    private static ProbedVideo probed(String videoCodec, String audioCodec, int width, int height) {
        return new ProbedVideo(30, videoCodec, audioCodec, width, height);
    }

    @Test
    void alreadyBrowserReadyFilesAreLeftAlone() {
        assertThat(probed("h264", "aac", 1280, 720).needsNormalizing()).isFalse();
        assertThat(probed("h264", "aac", 720, 1280).needsNormalizing()).isFalse(); // portrait
        assertThat(probed("h264", "aac", 640, 480).needsNormalizing()).isFalse();
    }

    @Test
    void aSilentFileIsNotReEncodedJustForHavingNoAudio() {
        assertThat(probed("h264", null, 1280, 720).needsNormalizing()).isFalse();
    }

    @Test
    void codecsBrowsersRefuseAreNormalized() {
        assertThat(probed("hevc", "aac", 1280, 720).needsNormalizing()).isTrue();   // iPhone
        assertThat(probed("av1", "aac", 1280, 720).needsNormalizing()).isTrue();
        assertThat(probed("vp8", "opus", 640, 480).needsNormalizing()).isTrue();    // browser recorder
        assertThat(probed("h264", "mp3", 1280, 720).needsNormalizing()).isTrue();
    }

    @Test
    void anythingLargerThan720pIsNormalizedInEitherOrientation() {
        assertThat(probed("h264", "aac", 1920, 1080).needsNormalizing()).isTrue();
        assertThat(probed("h264", "aac", 1080, 1920).needsNormalizing()).isTrue();
        // A rotated portrait capture is stored landscape, and is caught the same way.
        assertThat(probed("h264", "aac", 3840, 2160).needsNormalizing()).isTrue();
        // One side over the long-side cap is enough on its own.
        assertThat(probed("h264", "aac", 1600, 720).needsNormalizing()).isTrue();
    }
}
