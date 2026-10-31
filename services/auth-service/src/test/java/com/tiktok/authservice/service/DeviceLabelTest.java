package com.tiktok.authservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The label is a convenience, but it fails silently: a wrong answer still renders as a confident
 * line in the console, and every browser that matters lies about what it is. These are the lies.
 */
class DeviceLabelTest {

    /**
     * Both hints are the client's to write. A label past the column failed the INSERT, and the
     * recorder swallowed that — so an oversized hint kept the sender's own entry out of the log.
     */
    @Test
    void oversizedHintsAreCutToTheColumn() {
        String label = DeviceLabel.of("Mozilla/5.0 (Linux; Android 15) Chrome/140.0.0.0 Mobile Safari/537.36",
                "15." + "9".repeat(300), "X".repeat(500));

        assertThat(label).hasSize(DeviceLabel.MAX_LENGTH).startsWith("Chrome on Android 15.");
    }

    @Test
    void chromeIsNotMistakenForSafari() {
        // Chrome's agent ends in "Safari/537.36" and always has — matching on Safari first would
        // label most of the platform's traffic Safari.
        assertThat(DeviceLabel.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36", null, null))
                .isEqualTo("Chrome on macOS");
    }

    @Test
    void edgeIsNotMistakenForChrome() {
        // Edge claims to be Chrome, which claims to be Safari. Only "Edg/" tells the truth.
        assertThat(DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0", null, null))
                .isEqualTo("Edge on Windows");
    }

    @Test
    void operaIsNotMistakenForChrome() {
        assertThat(DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 OPR/125.0.0.0", null, null))
                .isEqualTo("Opera on Windows");
    }

    @Test
    void realSafariIsStillSafari() {
        assertThat(DeviceLabel.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1", null, null))
                .as("an iPhone agent also contains 'Mac OS X', so iOS has to be tested first")
                .isEqualTo("Safari on iOS");
    }

    @Test
    void androidBeatsLinux() {
        // Android agents contain "Linux; Android"; the more specific half is the useful one.
        assertThat(DeviceLabel.of("Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36", null, null))
                .isEqualTo("Chrome on Android");
    }

    @Test
    void halfAnAnswerIsBetterThanNone() {
        assertThat(DeviceLabel.of("curl/8.7.1", null, null)).isNull();
        assertThat(DeviceLabel.of("okhttp/4.12.0 (Linux)", null, null)).isEqualTo("Linux");
    }

    @Test
    void nothingToGoOnIsNull() {
        // Null rather than "Unknown": the console leaves the space empty instead of printing a
        // shrug next to every entry a background job wrote.
        assertThat(DeviceLabel.of(null, null, null)).isNull();
        assertThat(DeviceLabel.of("   ", null, null)).isNull();
    }

    // --- client hints ---------------------------------------------------------------------
    //
    // The agent is frozen, so these carry the only truthful OS version and the only device model
    // the web exposes. Chromium alone sends them.

    private static final String CHROME_ANDROID =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/140.0.0.0 Mobile Safari/537.36";
    private static final String CHROME_MAC =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    @Test
    void theModelIsNamedWhenTheBrowserWillNameIt() {
        assertThat(DeviceLabel.of(CHROME_ANDROID, "15.0.0", "SM-A525F"))
                .isEqualTo("Chrome on Android 15 · SM-A525F");
    }

    @Test
    void theFrozenAgentDoesNotDecideTheVersion() {
        // The agent says 10_15_7 on every Mac ever made. The hint is the real one.
        assertThat(DeviceLabel.of(CHROME_MAC, "15.1.0", ""))
                .isEqualTo("Chrome on macOS 15.1");
    }

    @Test
    void aDesktopThatCannotNameItselfIsNotMadeUp() {
        // Sec-CH-UA-Model is the empty string on every desktop platform, by specification. That is
        // a device with no name available, not a parse failure, and nothing may be invented for it.
        assertThat(DeviceLabel.of(CHROME_MAC, null, "")).isEqualTo("Chrome on macOS");
        assertThat(DeviceLabel.of(CHROME_MAC, null, null)).isEqualTo("Chrome on macOS");
    }

    @Test
    void chromesFrozenAndroidModelIsNotADevice() {
        // "K" is Chrome's stand-in for a model it will not disclose. Printed, it reads as a device.
        assertThat(DeviceLabel.of(CHROME_ANDROID, "15.0.0", "K"))
                .isEqualTo("Chrome on Android 15");
    }

    @Test
    void windowsVersionIsTranslatedRatherThanPrinted() {
        // Windows counts its platform hint separately from its name: 13+ is Windows 11, 1-12 is
        // Windows 10. Printed raw it would label a Windows 11 machine "Windows 15".
        assertThat(DeviceLabel.of(CHROME_WINDOWS, "15.0.0", "")).isEqualTo("Chrome on Windows 11");
        assertThat(DeviceLabel.of(CHROME_WINDOWS, "10.0.0", "")).isEqualTo("Chrome on Windows 10");
        // 7, 8 and 8.1 all report 0 and cannot be told apart, so no version is claimed.
        assertThat(DeviceLabel.of(CHROME_WINDOWS, "0.0.0", "")).isEqualTo("Chrome on Windows");
    }

    @Test
    void aBrowserWithoutHintsIsUnaffected() {
        // Safari and Firefox implement no client hints at all, so the label has to stay useful on
        // the agent alone — which is most of the log.
        assertThat(DeviceLabel.of("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1",
                null, null))
                .isEqualTo("Safari on iOS");
    }

    /**
     * The alert's label goes into a message the account's owner reads, and both hints are the
     * client's to write. The model is free text, so it never reaches the alert at all.
     */
    @Test
    void theAlertLabelNeverCarriesTheModel() {
        assertThat(DeviceLabel.brief("Mozilla/5.0 (Linux; Android 15) Chrome/140.0.0.0 Mobile Safari/537.36",
                "15.0.0"))
                .isEqualTo("Chrome on Android 15");
    }

    /** Otherwise "routine security check, no action needed" would ride into the owner's alert. */
    @Test
    void theAlertLabelDropsAVersionThatIsNotANumber() {
        String mac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/140.0.0.0 Safari/537.36";

        assertThat(DeviceLabel.brief(mac, "27; routine security check, no action needed"))
                .isEqualTo("Chrome on macOS");
        assertThat(DeviceLabel.brief(mac, "27.1.0")).isEqualTo("Chrome on macOS 27.1");
    }
}
