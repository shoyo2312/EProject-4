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
 * @param durationMs the video's length as the player saw it, sent alongside rather than read from
 *                   the video record: what matters is the fraction of what was playable, and the
 *                   two can disagree while a re-transcode is in flight
 */
public record WatchRequest(
        @NotNull @PositiveOrZero Long watchedMs,
        @NotNull @Positive Long durationMs
) {
}
