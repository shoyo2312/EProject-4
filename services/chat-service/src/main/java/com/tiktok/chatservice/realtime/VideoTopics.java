package com.tiktok.chatservice.realtime;

/**
 * The one place the realtime destinations are spelled out. The publisher and the subscription
 * tracker have to agree on the shape, and a mismatch is invisible — frames go to a destination
 * nobody listens on, and nothing logs an error.
 */
public final class VideoTopics {

    private static final String PREFIX = "/topic/videos.";
    private static final String COMMENTS_SUFFIX = ".comments";

    private VideoTopics() {
    }

    public static String video(String videoId) {
        return PREFIX + videoId;
    }

    public static String comments(String videoId) {
        return PREFIX + videoId + COMMENTS_SUFFIX;
    }

    /** @return the videoId a destination refers to, or null if it is not a video destination. */
    public static String videoIdOf(String destination) {
        if (destination == null || !destination.startsWith(PREFIX)) {
            return null;
        }
        String rest = destination.substring(PREFIX.length());
        if (rest.endsWith(COMMENTS_SUFFIX)) {
            rest = rest.substring(0, rest.length() - COMMENTS_SUFFIX.length());
        }
        return rest.isEmpty() ? null : rest;
    }
}
