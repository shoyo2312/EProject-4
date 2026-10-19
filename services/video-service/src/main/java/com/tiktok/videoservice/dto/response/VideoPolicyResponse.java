package com.tiktok.videoservice.dto.response;

import java.util.List;

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
 * <p>{@code commentAudience} is the owner's account-level setting, which only user-service knows —
 * carried here so interaction-service can apply it without a client of its own, on a call it was
 * already making. Relayed as the raw enum name ({@code EVERYONE}, {@code FRIENDS}, {@code NO_ONE}):
 * video-service does not interpret it, and a name it has never heard of is for the service that
 * enforces the setting to deal with, not a reason for this response to fail. Null means nobody
 * could say — user-service was unreachable — and the guard reads that as no restriction.
 *
 * <p>The owner's comment filters ride along for the same reason — interaction-service applies them
 * to every comment posted under the video: {@code filterSpamOffensive}, {@code filterAllComments},
 * {@code commentKeywords}, relayed as user-service holds them. Each is null when nobody could say,
 * which interaction-service reads as that filter's default (spam and offence filtered, nothing else).
 *
 * <p>Nothing here is more than a videoId already reveals to somebody who has one, except the
 * keywords — which is one more reason this path is denied at the gateway and checks the internal
 * token. An audience is visible from the video's own comment box anyway.
 */
public record VideoPolicyResponse(
        String videoId,
        Long userId,
        boolean commentsDisabled,
        Integer durationSeconds,
        String commentAudience,
        Boolean filterSpamOffensive,
        Boolean filterAllComments,
        List<String> commentKeywords
) {
}
