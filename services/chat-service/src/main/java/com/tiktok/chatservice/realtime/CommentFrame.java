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
        String createdAt
) {

    public static CommentFrame created(String videoId, String commentId, String userId,
                                       String content, String createdAt) {
        return new CommentFrame("comment.created", videoId, commentId, userId, content, createdAt);
    }

    public static CommentFrame deleted(String videoId, String commentId) {
        return new CommentFrame("comment.deleted", videoId, commentId, null, null, null);
    }
}
