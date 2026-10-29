package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.request.CreateConversationRequest;
import com.tiktok.chatservice.dto.request.MuteRequest;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessageRequestSummaryResponse;
import com.tiktok.chatservice.service.ConversationPrefsService;
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
    private final ConversationPrefsService prefsService;

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

    /** Pending requests waiting on the viewer. A literal path, so it outranks {@code /{conversationId}}. */
    @GetMapping("/requests")
    public ApiResponse<List<ConversationResponse>> requests(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(conversationService.listRequests(currentUserId));
    }

    @GetMapping("/requests/summary")
    public ApiResponse<MessageRequestSummaryResponse> requestSummary(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(conversationService.requestSummary(currentUserId));
    }

    @PostMapping("/{conversationId}/accept")
    public ApiResponse<ConversationResponse> accept(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(conversationService.acceptRequest(currentUserId, conversationId));
    }

    @PostMapping("/{conversationId}/decline")
    public ApiResponse<ConversationResponse> decline(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(conversationService.declineRequest(currentUserId, conversationId));
    }

    /** Pins the conversation above the caller's others; refused (409) when three are already pinned. */
    @PutMapping("/{conversationId}/pin")
    public ApiResponse<ConversationResponse> pin(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(prefsService.pin(currentUserId, conversationId));
    }

    @DeleteMapping("/{conversationId}/pin")
    public ApiResponse<ConversationResponse> unpin(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(prefsService.unpin(currentUserId, conversationId));
    }

    /** Silences alerts, for the caller only; messages still arrive. Calling it again changes the length. */
    @PutMapping("/{conversationId}/mute")
    public ApiResponse<ConversationResponse> mute(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId,
            @Valid @RequestBody MuteRequest request) {
        return ApiResponse.success(prefsService.mute(currentUserId, conversationId, request.duration()));
    }

    @DeleteMapping("/{conversationId}/mute")
    public ApiResponse<ConversationResponse> unmute(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(prefsService.unmute(currentUserId, conversationId));
    }

    /** Deletes the conversation from the caller's side only; the other person keeps their copy. */
    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteForMe(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        prefsService.deleteForMe(currentUserId, conversationId);
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationResponse> getById(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String conversationId) {
        return ApiResponse.success(conversationService.getById(currentUserId, conversationId));
    }
}
