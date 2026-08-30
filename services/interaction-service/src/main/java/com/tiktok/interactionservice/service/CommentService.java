package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.CommentLikeResponse;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;

public interface CommentService {

    /** Posts a reply when {@code parentId} is non-null, otherwise a top-level comment. */
    CommentResponse addComment(Long videoId, Long currentUserId, String content, Long parentId);

    default CommentResponse addComment(Long videoId, Long currentUserId, String content) {
        return addComment(videoId, currentUserId, content, null);
    }

    /**
     * {@code currentUserId} is null for an anonymous caller — the page then comes back with
     * {@code likedByMe} false throughout and no membership lookup is done.
     */
    CommentPageResponse listComments(Long videoId, String cursor, int size, Long currentUserId);

    default CommentPageResponse listComments(Long videoId, String cursor, int size) {
        return listComments(videoId, cursor, size, null);
    }

    void deleteComment(Long videoId, Long commentId, Long currentUserId);

    CommentLikeResponse likeComment(Long videoId, Long commentId, Long currentUserId);

    CommentLikeResponse unlikeComment(Long videoId, Long commentId, Long currentUserId);
}
