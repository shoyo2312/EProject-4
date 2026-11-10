package com.tiktok.authservice.service;

/**
 * What one request says about where it came from, read once per request by {@link RequestOrigins}
 * so that a sign-in's security-log entry and its new-device check agree on the same values.
 *
 * <p>Every field may be null: there is no request behind a Kafka consumer or a scheduled sweep,
 * and most browsers send no client hints.
 *
 * @param address         the client's address as api-gateway resolved it, or the socket's peer
 * @param userAgent       the raw agent
 * @param platformVersion the {@code Sec-CH-UA-Platform-Version} hint, unquoted
 * @param model           the {@code Sec-CH-UA-Model} hint, unquoted
 * @param deviceId        the client's {@code X-Device-Id}, when it fits the format; never a credential
 * @param location        the approximate place of {@code address}, or null
 */
public record RequestOrigin(
        String address,
        String userAgent,
        String platformVersion,
        String model,
        String deviceId,
        String location
) {

    public static final RequestOrigin NONE = new RequestOrigin(null, null, null, null, null, null);

    /** The security log's label: {@code "Chrome on Android 15 · Pixel 9"}. */
    public String deviceLabel() {
        return DeviceLabel.of(userAgent, platformVersion, model);
    }

    /** The new-device alert's label, in fixed words only: {@code "Chrome on Android 15"}. */
    public String alertLabel() {
        return DeviceLabel.brief(userAgent, platformVersion);
    }
}
