package com.tiktok.interactionservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * How much of the video the player actually played, reported once the session ends — the client
 * knows this and the server cannot infer it.
 *
 * @param watchedMs  total played, summed across replays inside the session, so a looping video
 *                   reports more than its duration rather than starting over
 * @param durationMs the video's length as the player saw it. Advisory only — the server prefers
 *                   the duration video-service probed and falls back to this one, clamped, when
 *                   there is none yet, because a client that sends watchedMs and durationMs equal
 *                   would otherwise report a completion for every request
 */
public record WatchRequest(
        @NotNull @PositiveOrZero Long watchedMs,
        @NotNull @Positive Long durationMs
) {
}
