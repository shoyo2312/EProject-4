package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.request.AddCommentRequest;
import com.tiktok.interactionservice.dto.response.CommentLikeResponse;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/interactions")
@RequiredArgsConstructor
public class CommentController {

    /**
     * The most comments one request may ask Cassandra for. Clamped here rather than trusted,
     * because {@code size} goes straight into the driver's fetch size: zero and negatives are
     * rejected by the driver as an illegal argument — a 500 on a query string — and a large one
     * is a page nobody asked for, read in full. Same ceiling video-service applies to its own
     * listings.
     */
    private static final int MAX_PAGE_SIZE = 50;

    private final CommentService commentService;

    @PostMapping("/videos/{videoId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentResponse> addComment(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @Valid @RequestBody AddCommentRequest request) {
        return ApiResponse.success(
                commentService.addComment(videoId, currentUserId, request.content(), request.parentId()));
    }

    @GetMapping("/videos/{videoId}/comments")
    public ApiResponse<CommentPageResponse> listComments(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(commentService.listComments(
                videoId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE), currentUserId));
    }

    @DeleteMapping("/videos/{videoId}/comments/{commentId}")
    public ApiResponse<Void> deleteComment(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @PathVariable Long commentId) {
        commentService.deleteComment(videoId, commentId, currentUserId);
        return ApiResponse.success(null);
    }

    @PostMapping("/videos/{videoId}/comments/{commentId}/like")
    public ApiResponse<CommentLikeResponse> likeComment(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @PathVariable Long commentId) {
        return ApiResponse.success(commentService.likeComment(videoId, commentId, currentUserId));
    }

    @DeleteMapping("/videos/{videoId}/comments/{commentId}/like")
    public ApiResponse<CommentLikeResponse> unlikeComment(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @PathVariable Long commentId) {
        return ApiResponse.success(commentService.unlikeComment(videoId, commentId, currentUserId));
    }
}
