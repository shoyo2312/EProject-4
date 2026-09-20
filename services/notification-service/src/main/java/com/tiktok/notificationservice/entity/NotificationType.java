package com.tiktok.notificationservice.entity;

public enum NotificationType {
    LIKE,
    COMMENT,
    SHARE,
    NEW_FOLLOWER,
    SYSTEM;

    /**
     * True for the types whose event is a flag being flipped rather than a piece of content.
     * Un-liking and re-liking the same video emits a fresh event with a fresh eventId every
     * time, so idempotency on the event cannot help: to the inbox they are distinct likes, and
     * the owner ends up with one entry per toggle for a single real admirer.
     *
     * <p>COMMENT is excluded — two comments by the same person on the same video are two things
     * worth being told about, and they share a referenceId (the video), so collapsing them would
     * hide the second. SYSTEM has no actor to collapse against.
     */
    public boolean collapsesRepeats() {
        return this == LIKE || this == SHARE || this == NEW_FOLLOWER;
    }
}
