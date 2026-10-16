package com.tiktok.interactionservice.dto.response;

import com.tiktok.interactionservice.entity.CommentStatus;

import java.time.Instant;

/**
 * A comment as the moderation console sees it. Unlike {@link CommentResponse} it carries
 * {@code deletedAt}: an admin reviewing a thread needs to see what has already been removed,
 * otherwise a comment somebody else took down is indistinguishable from one that never existed.
 * There is no {@code likedByMe} — nobody is signed in as a viewer here.
 */
public record AdminCommentResponse(
        Long commentId,
        Long videoId,
        Long userId,
        String content,
        Long parentId,
        Long replyToUserId,
        int likeCount,
        /**
         * Replies still standing under this comment, and 0 on a reply — the thread is one level
         * deep. Counts live replies only: the tally comes off {@code comment_counters}, which a
         * removal decrements, so a thread whose replies were all removed reads 0 while expanding
         * it still shows them. The console needs the number to decide whether to offer "View
         * replies" at all, and a stale-by-removals count is the cheap way to get it — the exact
         * figure costs a read of the whole reply partition.
         */
        int replyCount,
        /**
         * Whether anything at all hangs under this comment, removed replies included — the one
         * thing {@code replyCount} cannot say. Read off {@code comment_index}, which keeps a row
         * per reply whether or not the reply was later removed, so a thread whose replies were all
         * taken down still reports true here and stays openable in the console.
         */
        boolean hasReplies,
        Instant createdAt,
        Instant deletedAt,
        /** HELD while it waits for the video's owner; such a comment was never public. */
        CommentStatus status,
        /** What the text rules flagged, e.g. {@code SPAM:link:bit.ly}; null when nothing was. */
        String moderationReason
) {
}
