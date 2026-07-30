package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;

public interface CommentService {

    CommentResponse addComment(Long videoId, Long currentUserId, String content);

    CommentPageResponse listComments(Long videoId, String cursor, int size);

    void deleteComment(Long videoId, Long commentId, Long currentUserId);
}
