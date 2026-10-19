package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.CommentAuthorResponse;
import com.tiktok.interactionservice.service.AdminCommentDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service reads, for callers that act on a comment without a signed-in user behind them.
 * Tokenless: {@code SecurityConfig} permits this prefix, and api-gateway denies it outright, the same
 * arrangement as user-service's {@code /users/internal/**} and video-service's {@code /policy}.
 */
@RestController
@RequestMapping("/api/v1/interactions/internal")
@RequiredArgsConstructor
public class InternalCommentController {

    private final AdminCommentDirectory adminCommentDirectory;

    /**
     * The author of one comment, removed or not. notification-service asks this when an admin warns
     * over a comment report: the event names the comment, and the person to tell is whoever wrote
     * it. 404 COMMENT_NOT_FOUND for a comment this service has never stored.
     */
    @GetMapping("/videos/{videoId}/comments/{commentId}/author")
    public ApiResponse<CommentAuthorResponse> author(@PathVariable Long videoId, @PathVariable Long commentId) {
        return ApiResponse.success(adminCommentDirectory.authorOf(videoId, commentId));
    }
}
