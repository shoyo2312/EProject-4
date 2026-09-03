package com.tiktok.videoservice.dto.response;

/**
 * What another service needs to know about a video to enforce rules on its behalf: who owns it,
 * and whether its owner has switched comments off.
 *
 * <p>Deliberately not a trimmed {@link VideoResponse}. The full response is filtered by visibility
 * — a PRIVATE or still-PROCESSING video is a 404 to everyone but its owner — and interaction-service
 * calls with no token at all, so reading these two fields off that endpoint answered 404 for exactly
 * the videos where the answer matters: the owner of a private video could not delete comments on it,
 * and comments switched off on one stayed on, because the lookup fails open.
 *
 * <p>Also carries the probed duration, which is the only trustworthy copy of it: the client
 * reports its own watchedMs and durationMs when a play ends, and with nothing to compare them
 * against, sending the two equal makes every session a completion — the highest-value label the
 * ranker has. Null until the transcode has probed the file.
 *
 * <p>Nothing here is more than a videoId already reveals to somebody who has one.
 */
public record VideoPolicyResponse(
        String videoId,
        Long userId,
        boolean commentsDisabled,
        Integer durationSeconds
) {
}
