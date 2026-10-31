package com.tiktok.chatservice.entity;

/**
 * Whether a viewer's alerts from one conversation are silenced right now. {@code NONE} is the answer
 * for a conversation that was never muted and for one whose timed mute has run out; the stored record
 * of a timed mute is not the answer, the clock is — see {@link ChatUserPrefs#muteModeAt}.
 */
public enum MuteMode {
    NONE,
    /** Until {@code mutedUntil}. */
    TIMED,
    /** Until the viewer says otherwise; {@code mutedUntil} is null. */
    INDEFINITE
}
