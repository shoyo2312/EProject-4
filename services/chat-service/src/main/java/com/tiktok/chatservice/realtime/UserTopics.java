package com.tiktok.chatservice.realtime;

/** The one place {@code /topic/users.{userId}} is spelled out — see {@code VideoTopics}. */
public final class UserTopics {

    private static final String PREFIX = "/topic/users.";

    private UserTopics() {
    }

    public static String user(Long userId) {
        return PREFIX + userId;
    }

    /** @return the userId a destination refers to, or null if it is not a user destination. */
    public static String userIdOf(String destination) {
        if (destination == null || !destination.startsWith(PREFIX)) {
            return null;
        }
        String rest = destination.substring(PREFIX.length());
        return rest.isEmpty() ? null : rest;
    }
}
