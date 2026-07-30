package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.request.AddCommentRequest;
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

    private final CommentService commentService;

    @PostMapping("/videos/{videoId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentResponse> addComment(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @Valid @RequestBody AddCommentRequest request) {
        return ApiResponse.success(commentService.addComment(videoId, currentUserId, request.content()));
    }

    @GetMapping("/videos/{videoId}/comments")
    public ApiResponse<CommentPageResponse> listComments(
            @PathVariable Long videoId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(commentService.listComments(videoId, cursor, size));
    }

    @DeleteMapping("/videos/{videoId}/comments/{commentId}")
    public ApiResponse<Void> deleteComment(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long videoId,
            @PathVariable Long commentId) {
        commentService.deleteComment(videoId, commentId, currentUserId);
        return ApiResponse.success(null);
    }
}
