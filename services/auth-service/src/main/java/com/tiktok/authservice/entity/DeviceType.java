package com.tiktok.authservice.entity;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The two device slots an account has: one laptop/PC and one phone/tablet. Signing in on a second
 * device of the same type signs the first one out (see {@code SessionRegistry}).
 *
 * <p>Read from the user agent. Tablets go with phones: an Android tablet has no "Mobile" token but
 * does say Android, and an iPad says iPad — except Safari on iPadOS, which asks for desktop sites
 * by default and claims to be a Mac. Nothing a server sees tells that one apart, so it takes the
 * desktop slot; the mobile app always says what it is.
 */
public enum DeviceType {

    DESKTOP,

    MOBILE;

    private static final Pattern MOBILE_AGENT =
            Pattern.compile("android|iphone|ipad|ipod|mobile|tablet|silk|kindle|windows phone");

    /** {@link #DESKTOP} when the agent is missing — an API client, a script. */
    public static DeviceType fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return DESKTOP;
        }
        return MOBILE_AGENT.matcher(userAgent.toLowerCase(Locale.ROOT)).find() ? MOBILE : DESKTOP;
    }
}
