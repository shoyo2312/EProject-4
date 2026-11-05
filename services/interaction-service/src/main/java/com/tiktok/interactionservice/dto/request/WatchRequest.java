package com.tiktok.interactionservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * How much of the video the player actually played, reported once the session ends — the client
 * knows this and the server cannot infer it.
 *
 * @param watchedMs   total played, summed across replays inside the session, so a looping video
 *                    reports more than its duration rather than starting over
 * @param durationMs  the video's length as the player saw it. Advisory only — the server prefers
 *                    the duration video-service probed and falls back to this one, clamped, when
 *                    there is none yet, because a client that sends watchedMs and durationMs equal
 *                    would otherwise report a completion for every request
 * @param source      the surface the video played on. Optional: the mobile app does not send it
 *                    yet, and a missing source is recorded as {@code OTHER}
 * @param searchQuery the search phrase that led here; only kept with {@code SEARCH}, trimmed and
 *                    lower-cased by the server
 */
public record WatchRequest(
        @NotNull @PositiveOrZero Long watchedMs,
        @NotNull @Positive Long durationMs,
        WatchSource source,
        @Size(max = 100) String searchQuery
) {

    public WatchRequest(Long watchedMs, Long durationMs) {
        this(watchedMs, durationMs, null, null);
    }
}
