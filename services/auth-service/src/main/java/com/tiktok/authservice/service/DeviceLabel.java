package com.tiktok.authservice.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns what the browser said into something readable in a list — {@code "Chrome on macOS 15.1"},
 * {@code "Chrome on Android 15 · SM-A525F"}.
 *
 * <p>Deliberately not a user-agent parsing library. The full grammar is a moving target, and a
 * dependency is worth it only when something depends on the answer; nothing does. This label sits
 * beside the raw agent and the raw model, both stored intact, so being vague about an unusual
 * browser costs a vague line in the console and loses no evidence.
 *
 * <p>Two sources, because one is no longer enough. The agent gives the browser and the platform
 * family and nothing else true — it is frozen for privacy, so every Mac claims to be an Intel
 * machine on 10.15 and every iPhone claims only to be an iPhone. The real OS version and the
 * device model arrive as client hints, which only Chromium sends. Where they are absent the label
 * degrades to what the agent alone supports, which is the honest floor.
 *
 * <p>Order is the whole trick in the agent. Edge and Opera both claim to be Chrome, and Chrome
 * claims to be Safari, so the most specific token has to be tested first.
 */
final class DeviceLabel {

    private record Token(String needle, String label) {
    }

    private static final List<Token> BROWSERS = List.of(
            new Token("edg/", "Edge"),
            new Token("opr/", "Opera"),
            new Token("opera", "Opera"),
            new Token("samsungbrowser", "Samsung Internet"),
            new Token("chrome", "Chrome"),
            new Token("firefox", "Firefox"),
            new Token("safari", "Safari"));

    private static final List<Token> PLATFORMS = List.of(
            new Token("iphone", "iOS"),
            new Token("ipad", "iPadOS"),
            new Token("android", "Android"),
            new Token("windows nt", "Windows"),
            new Token("mac os x", "macOS"),
            new Token("linux", "Linux"));

    /**
     * Windows counts its platform-version hint separately from its marketing name: 13 and up mean
     * Windows 11, 1 through 12 mean Windows 10. Printed raw, every Windows 11 machine would be
     * labelled "Windows 15". Below 1 cannot be told apart at all — 7, 8 and 8.1 all report 0 — so
     * the version is left off rather than guessed.
     */
    private static final int WINDOWS_11_PLATFORM_MAJOR = 13;

    /**
     * The {@code device_label} column (V12). The model and the platform version are copied in from
     * client hints, which a client sets to whatever it likes; past the column, the INSERT failed and
     * the recorder — which swallows every failure so a log can never block a sign-in — dropped the
     * entry. A long {@code Sec-CH-UA-Model} was therefore a way to keep one's own failed sign-ins
     * out of the account's security log.
     */
    static final int MAX_LENGTH = 128;

    /** What an OS version looks like when it is only a version: {@code 27}, {@code 15.1.0}. */
    private static final Pattern NUMERIC_VERSION = Pattern.compile("\\d+(\\.\\d+)*");

    private DeviceLabel() {
    }

    /**
     * @param userAgent       the raw agent, or null
     * @param platformVersion the {@code Sec-CH-UA-Platform-Version} hint, already unquoted, or null
     * @param model           the {@code Sec-CH-UA-Model} hint, already unquoted, or null
     * @return {@code "Chrome on Android 15 · Pixel 9"} down to {@code "Firefox"}, or null when the
     *         agent is missing or names nothing known — null rather than "Unknown", so the console
     *         can leave the space empty instead of printing a shrug next to every background job.
     */
    static String of(String userAgent, String platformVersion, String model) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }

        String agent = userAgent.toLowerCase(Locale.ROOT);
        String browser = firstMatch(agent, BROWSERS);
        String platform = firstMatch(agent, PLATFORMS);

        if (platform != null) {
            platform = withVersion(platform, platformVersion);
        }

        String head = browser != null && platform != null
                ? browser + " on " + platform
                : browser != null ? browser : platform;

        if (head == null) {
            return null;
        }
        // A model with no browser or platform to hang it on is still worth more than nothing.
        String label = usableModel(model) ? head + " · " + model.strip() : head;
        return label.length() <= MAX_LENGTH ? label : label.substring(0, MAX_LENGTH);
    }

    /**
     * The label for the new-device alert, which the account's owner reads: the browser and the
     * platform from the fixed lists, with the OS version only when it is digits and dots, and
     * never the model. Both hints are the client's to write, and {@link #of} keeps them as sent —
     * fine for a console that also shows the raw agent, not for a message that would otherwise
     * carry "routine security check, no action needed" to the person it is warning.
     *
     * @return {@code "Chrome on macOS 27"} down to {@code "Firefox"}, or null as {@link #of}
     */
    static String brief(String userAgent, String platformVersion) {
        String version = platformVersion == null ? null : platformVersion.strip();
        boolean numeric = version != null && NUMERIC_VERSION.matcher(version).matches();
        return of(userAgent, numeric ? version : null, null);
    }

    private static String withVersion(String platform, String platformVersion) {
        if (platformVersion == null || platformVersion.isBlank()) {
            return platform;
        }

        String version = trimTrailingZeroes(platformVersion.strip());
        if (version.isEmpty()) {
            return platform;
        }

        if (platform.equals("Windows")) {
            Integer major = major(version);
            if (major == null || major < 1) {
                return platform;
            }
            return major >= WINDOWS_11_PLATFORM_MAJOR ? "Windows 11" : "Windows 10";
        }

        return platform + " " + version;
    }

    /** {@code 15.0.0} is how a browser writes 15, and {@code 15.1.0} how it writes 15.1. */
    private static String trimTrailingZeroes(String version) {
        String trimmed = version;
        while (trimmed.endsWith(".0")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        return trimmed.equals("0") ? "" : trimmed;
    }

    private static Integer major(String version) {
        String head = version.split("\\.", 2)[0];
        try {
            return Integer.valueOf(head);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The hint is an empty string on every desktop platform, by specification rather than by
     * accident. {@code "K"} is Chrome's frozen stand-in for an Android model and names no device.
     */
    private static boolean usableModel(String model) {
        return model != null && !model.isBlank() && !model.strip().equals("K");
    }

    private static String firstMatch(String agent, List<Token> tokens) {
        return tokens.stream()
                .filter(token -> agent.contains(token.needle()))
                .map(Token::label)
                .findFirst()
                .orElse(null);
    }
}
