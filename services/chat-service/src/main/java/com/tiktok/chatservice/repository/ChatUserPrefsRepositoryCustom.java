package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.MuteMode;

import java.time.Instant;

public interface ChatUserPrefsRepositoryCustom {

    enum PinOutcome {
        PINNED,
        /** Already pinned: nothing changed, and the pin keeps its place. */
        ALREADY_PINNED,
        /** The viewer already has {@code MAX_PINS} others pinned. */
        FULL
    }

    /**
     * Pins the conversation above the user's others — {@code at} is raised if need be so it is later
     * than every existing pin — unless that would make more than {@code MAX_PINS}.
     *
     * <p>The limit is enforced by the update's own filter on the user's single document (a fourth slot
     * does not exist, and the conversation is not already in the list), not by counting first and
     * writing after: two devices pinning at once cannot both pass a check that is the write itself.
     */
    PinOutcome pin(Long userId, String conversationId, Instant at);

    /** Gives the slot back. Repeating it changes nothing. */
    void unpin(Long userId, String conversationId);

    /** {@code until} is required for TIMED and ignored for INDEFINITE. Replaces any earlier mute. */
    void mute(Long userId, String conversationId, MuteMode mode, Instant until);

    void unmute(Long userId, String conversationId);

    /**
     * Records that the user deleted the conversation from their side at {@code at}, and releases its pin.
     * The mute is deliberately kept. {@code at} only moves forward, so a retry or a slower duplicate
     * cannot bring older history back.
     */
    void clear(Long userId, String conversationId, Instant at);
}
