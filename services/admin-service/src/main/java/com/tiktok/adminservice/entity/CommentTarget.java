package com.tiktok.adminservice.entity;

/**
 * The {@code targetId} of a COMMENT moderation action, as the {@code "videoId:commentId"} pair.
 *
 * <p>One id would be the obvious choice and is the wrong one: comments live in Cassandra under
 * {@code comments_by_video}, partitioned by video, so the comment id alone reaches nothing. The
 * pair is the comment's actual identity in this system, and the audit row has to carry enough to
 * act on later — a stored id that nobody can resolve back to a comment is not an audit trail.
 */
public record CommentTarget(Long videoId, Long commentId) {

    public static CommentTarget parse(String targetId) {
        int separator = targetId == null ? -1 : targetId.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException(
                    "COMMENT targetId must be \"videoId:commentId\", got: " + targetId);
        }
        long videoId = Long.parseLong(targetId.substring(0, separator));
        long commentId = Long.parseLong(targetId.substring(separator + 1));
        if (videoId <= 0 || commentId <= 0) {
            throw new IllegalArgumentException(
                    "COMMENT targetId must hold two positive ids, got: " + targetId);
        }
        return new CommentTarget(videoId, commentId);
    }

    public String targetId() {
        return videoId + ":" + commentId;
    }
}
