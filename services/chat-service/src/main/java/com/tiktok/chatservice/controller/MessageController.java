package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageResponse> send(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.success(messageService.sendMessage(conversationId, currentUserId, request));
    }

    @GetMapping
    public ApiResponse<MessagePageResponse> list(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(messageService.listMessages(conversationId, currentUserId, cursor, size));
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        messageService.markRead(conversationId, currentUserId);
    }
}
