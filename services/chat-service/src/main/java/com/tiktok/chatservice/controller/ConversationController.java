package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.request.CreateConversationRequest;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.service.ConversationService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> create(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody CreateConversationRequest request) {
        return ApiResponse.success(conversationService.getOrCreate(currentUserId, request.participantId()));
    }

    @GetMapping
    public ApiResponse<List<ConversationResponse>> list(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(conversationService.listForUser(currentUserId));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationResponse> getById(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(conversationService.getById(currentUserId, conversationId));
    }
}
