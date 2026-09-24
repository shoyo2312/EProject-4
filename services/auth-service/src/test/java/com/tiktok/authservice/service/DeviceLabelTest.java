package com.tiktok.authservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The label is a convenience, but it fails silently: a wrong answer still renders as a confident
 * line in the console, and every browser that matters lies about what it is. These are the lies.
 */
class DeviceLabelTest {

    @Test
    void chromeIsNotMistakenForSafari() {
        // Chrome's agent ends in "Safari/537.36" and always has — matching on Safari first would
        // label most of the platform's traffic Safari.
        assertThat(DeviceLabel.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"))
                .isEqualTo("Chrome on macOS");
    }

    @Test
    void edgeIsNotMistakenForChrome() {
        // Edge claims to be Chrome, which claims to be Safari. Only "Edg/" tells the truth.
        assertThat(DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0"))
                .isEqualTo("Edge on Windows");
    }

    @Test
    void operaIsNotMistakenForChrome() {
        assertThat(DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 OPR/125.0.0.0"))
                .isEqualTo("Opera on Windows");
    }

    @Test
    void realSafariIsStillSafari() {
        assertThat(DeviceLabel.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"))
                .as("an iPhone agent also contains 'Mac OS X', so iOS has to be tested first")
                .isEqualTo("Safari on iOS");
    }

    @Test
    void androidBeatsLinux() {
        // Android agents contain "Linux; Android"; the more specific half is the useful one.
        assertThat(DeviceLabel.of("Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"))
                .isEqualTo("Chrome on Android");
    }

    @Test
    void halfAnAnswerIsBetterThanNone() {
        assertThat(DeviceLabel.of("curl/8.7.1")).isNull();
        assertThat(DeviceLabel.of("okhttp/4.12.0 (Linux)")).isEqualTo("Linux");
    }

    @Test
    void nothingToGoOnIsNull() {
        // Null rather than "Unknown": the console leaves the space empty instead of printing a
        // shrug next to every entry a background job wrote.
        assertThat(DeviceLabel.of(null)).isNull();
        assertThat(DeviceLabel.of("   ")).isNull();
    }
}
