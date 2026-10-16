package com.tiktok.interactionservice.client;

import java.util.List;

/**
 * Everything the comment guard needs about a video, from the one {@code /policy} read it already
 * made: who owns it, whether its own comment switch is off, the owner's account-level audience, and
 * the owner's comment filters.
 *
 * <p>One object rather than several questions, because each question used to be its own HTTP call —
 * asking them separately turned one guard into three round trips on the comment path.
 *
 * @param ownerId             null when video-service could not be reached, or the video is gone
 * @param commentsDisabled    the per-video switch, false when nobody could say (fails open)
 * @param commentAudience     the owner's setting, {@link CommentAudience#EVERYONE} when nobody could
 *                            say (fails open)
 * @param filterSpamOffensive false: comments are not held for profanity, insults or spam. True when
 *                            nobody could say — the protective default every account starts with
 * @param filterAllComments   every comment from someone else waits for the owner. False when nobody
 *                            could say: holding every comment on the platform during an outage would
 *                            be the guard failing closed
 * @param commentKeywords     the owner's keywords; empty when nobody could say
 */
public record CommentPolicy(
        Long ownerId,
        boolean commentsDisabled,
        CommentAudience commentAudience,
        boolean filterSpamOffensive,
        boolean filterAllComments,
        List<String> commentKeywords
) {

    public CommentPolicy {
        commentKeywords = commentKeywords == null ? List.of() : List.copyOf(commentKeywords);
    }

    /** A policy whose owner left every comment filter at its default. */
    public CommentPolicy(Long ownerId, boolean commentsDisabled, CommentAudience commentAudience) {
        this(ownerId, commentsDisabled, commentAudience, true, false, List.of());
    }

    /**
     * What every fail-open path answers: no owner to exempt, no switch set, no audience to
     * enforce, the filters at their defaults. Named rather than repeated so "what does this guard do
     * when the lookup fails" has one answer, and so a test can say it without spelling out the fields.
     */
    public static CommentPolicy unrestricted() {
        return new CommentPolicy(null, false, CommentAudience.EVERYONE);
    }
}
