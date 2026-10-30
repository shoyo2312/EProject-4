package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.request.StoryReplyRequest;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.service.StoryReplyService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations/story-replies")
@RequiredArgsConstructor
public class StoryReplyController {

    private final StoryReplyService storyReplyService;

    /** 201 for a new message, 200 when the same clientRequestId was already delivered. */
    @PostMapping
    public ResponseEntity<ApiResponse<MessageResponse>> reply(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody StoryReplyRequest request) {
        MessageResponse response = storyReplyService.reply(currentUserId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
}
