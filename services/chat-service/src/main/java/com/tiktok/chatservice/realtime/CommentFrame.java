package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message on {@code /topic/videos.{videoId}.comments}. Ids are Strings for the same reason as
 * {@link VideoFrame}. Not coalesced: two comments are two facts, and gathering them into one
 * frame would lose one of them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommentFrame(
        String type,
        String videoId,
        String commentId,
        String userId,
        String content,
        String createdAt,
        String parentId,
        String replyToUserId,
        Integer likeCount
) {

    public static CommentFrame created(String videoId, String commentId, String userId,
                                       String content, String createdAt,
                                       String parentId, String replyToUserId) {
        return new CommentFrame("comment.created", videoId, commentId, userId, content, createdAt,
                parentId, replyToUserId, null);
    }

    public static CommentFrame deleted(String videoId, String commentId) {
        return new CommentFrame("comment.deleted", videoId, commentId, null, null, null, null, null, null);
    }

    /** The settled tally on one comment. Carries no author or content — nothing else moved. */
    public static CommentFrame liked(String videoId, String commentId, int likeCount) {
        return new CommentFrame("comment.liked", videoId, commentId, null, null, null, null, null,
                likeCount);
    }
}
