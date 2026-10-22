package com.tiktok.notificationservice.entity;

public enum NotificationType {
    LIKE,
    COMMENT,
    COMMENT_LIKE,
    SHARE,
    NEW_FOLLOWER,
    /** Someone asked to follow the recipient's private account; actor and reference are the requester. */
    FOLLOW_REQUEST,
    /** The recipient's follow request was approved; actor and reference are the account now followed. */
    FOLLOW_REQUEST_ACCEPTED,
    /**
     * An account the recipient follows posted a story; actor and reference are that account, which
     * is where a tap goes. The reference is the owner rather than the story so that it doubles as
     * the collapse key.
     */
    STORY,
    SYSTEM;

    /**
     * True for the types whose event is a flag being flipped rather than a piece of content.
     * Un-liking and re-liking the same video emits a fresh event with a fresh eventId every
     * time, so idempotency on the event cannot help: to the inbox they are distinct likes, and
     * the owner ends up with one entry per toggle for a single real admirer. Asking to follow,
     * withdrawing and asking again is the same story.
     *
     * <p>STORY is not a flag but is collapsed too: its reference is the owner, so a follower gets
     * one entry per owner per window — not a second for the owner's next story, and not a
     * duplicate when a fan-out that failed halfway is retried from its first page.
     *
     * <p>COMMENT is excluded — two comments by the same person on the same video are two things
     * worth being told about, and they share a referenceId (the video), so collapsing them would
     * hide the second. SYSTEM has no actor to collapse against.
     */
    public boolean collapsesRepeats() {
        return this == LIKE || this == COMMENT_LIKE || this == SHARE || this == NEW_FOLLOWER
                || this == FOLLOW_REQUEST || this == FOLLOW_REQUEST_ACCEPTED || this == STORY;
    }
}
