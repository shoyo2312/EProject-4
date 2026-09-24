package com.tiktok.interactionservice.client;

/**
 * Everything the comment guard needs about a video, from the one {@code /policy} read it already
 * made: who owns it, whether its own comment switch is off, and the owner's account-level
 * audience.
 *
 * <p>One object rather than three questions, because each question used to be its own HTTP call —
 * asking them separately turned one guard into three round trips on the comment path.
 *
 * @param ownerId        null when video-service could not be reached, or the video is gone
 * @param commentsDisabled the per-video switch, false when nobody could say (fails open)
 * @param commentAudience the owner's setting, {@link CommentAudience#EVERYONE} when nobody could
 *                        say (fails open)
 */
public record CommentPolicy(
        Long ownerId,
        boolean commentsDisabled,
        CommentAudience commentAudience
) {

    /**
     * What every fail-open path answers: no owner to exempt, no switch set, no audience to
     * enforce. Named rather than repeated so "what does this guard do when the lookup fails" has
     * one answer, and so a test can say it without spelling out three fields.
     */
    public static CommentPolicy unrestricted() {
        return new CommentPolicy(null, false, CommentAudience.EVERYONE);
    }
}
