package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.request.AckDeliveredRequest;
import com.tiktok.chatservice.dto.request.AckReadRequest;
import com.tiktok.chatservice.dto.request.ReactionRequest;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;
import com.tiktok.chatservice.dto.response.ReceiptView;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
@RequiredArgsConstructor
@Validated
public class MessageController {

    private final MessageService messageService;

    @PostMapping
    public ResponseEntity<ApiResponse<MessageResponse>> send(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        MessageResponse response = messageService.sendMessage(conversationId, currentUserId, request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.success(response));
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

    /** The caller's client has received these messages of the other side; answers with the caller's own marks. */
    @PostMapping("/delivered")
    public ApiResponse<ReceiptView> markDelivered(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @Valid @RequestBody AckDeliveredRequest request) {
        return ApiResponse.success(messageService.markDelivered(conversationId, currentUserId, request.messageIds()));
    }

    @GetMapping("/receipts")
    public ApiResponse<ReceiptView> receipts(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(messageService.receipts(conversationId, currentUserId));
    }

    /**
     * The caller has looked at the thread up to {@code messageId}. A body-less call, from a client from
     * before read positions existed, reads through the newest message of the other side as of now.
     */
    @PostMapping("/read")
    public ApiResponse<ReceiptView> markRead(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @Valid @RequestBody(required = false) AckReadRequest request) {
        return ApiResponse.success(messageService.markRead(conversationId, currentUserId,
                request == null ? null : request.messageId()));
    }
}
