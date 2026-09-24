package com.tiktok.authservice.service;

import java.util.List;
import java.util.Locale;

/**
 * Turns a user agent into something readable in a list — {@code "Chrome on macOS"}.
 *
 * <p>Deliberately not a user-agent parsing library. The full grammar is a moving target, and a
 * dependency is worth it only when something depends on the answer; nothing does. This label sits
 * beside the raw agent, which is stored intact, so being vague about an unusual browser costs a
 * vague line in the console and loses no evidence.
 *
 * <p>Order is the whole trick. Edge and Opera both claim to be Chrome, and Chrome claims to be
 * Safari, so the most specific token has to be tested first.
 */
final class DeviceLabel {

    private record Token(String needle, String label) {
    }

    private static final List<Token> BROWSERS = List.of(
            new Token("edg/", "Edge"),
            new Token("opr/", "Opera"),
            new Token("opera", "Opera"),
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

    private DeviceLabel() {
    }

    /**
     * @return {@code "Chrome on macOS"}, whichever half is recognised when only one is, or null
     *         when the agent is missing or names nothing known — null rather than "Unknown", so
     *         the console can leave the space empty instead of printing a shrug.
     */
    static String of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }

        String agent = userAgent.toLowerCase(Locale.ROOT);
        String browser = firstMatch(agent, BROWSERS);
        String platform = firstMatch(agent, PLATFORMS);

        if (browser != null && platform != null) {
            return browser + " on " + platform;
        }
        return browser != null ? browser : platform;
    }

    private static String firstMatch(String agent, List<Token> tokens) {
        return tokens.stream()
                .filter(token -> agent.contains(token.needle()))
                .map(Token::label)
                .findFirst()
                .orElse(null);
    }
}
