package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.request.ReactionRequest;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
@RequiredArgsConstructor
@Validated
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
            // Bounded: the value goes straight into the query's limit, so an unbounded one is a
            // request to load a whole conversation into memory.
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(messageService.listMessages(conversationId, currentUserId, cursor, size));
    }

    /** Idempotent: the same call twice leaves the same reaction. Replaces any other reaction of the caller's. */
    @PutMapping("/{messageId}/reaction")
    public ApiResponse<MessageUpdateResponse> react(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @PathVariable String messageId,
            @Valid @RequestBody ReactionRequest request) {
        return ApiResponse.success(messageService.react(conversationId, currentUserId, messageId, request.emoji()));
    }

    @DeleteMapping("/{messageId}/reaction")
    public ApiResponse<MessageUpdateResponse> unreact(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @PathVariable String messageId) {
        return ApiResponse.success(messageService.unreact(conversationId, currentUserId, messageId));
    }

    /** For everyone, by the sender only. */
    @PostMapping("/{messageId}/recall")
    public ApiResponse<MessageUpdateResponse> recall(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @PathVariable String messageId) {
        return ApiResponse.success(messageService.recall(conversationId, currentUserId, messageId));
    }

    /** From the caller's own side only. */
    @PostMapping("/{messageId}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @PathVariable String messageId) {
        messageService.hide(conversationId, currentUserId, messageId);
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        messageService.markRead(conversationId, currentUserId);
    }
}
