package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.response.ConversationExportResponse;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Service-to-service: a user's chat history for user-service's "Download your data" job, which
 * runs with no signed-in user to forward. api-gateway denies this prefix; {@code SecurityConfig}
 * checks the internal token, like user-service's {@code /users/internal/**}.
 */
@RestController
@RequestMapping("/api/v1/conversations/internal")
@RequiredArgsConstructor
@Validated
public class InternalChatExportController {

    private final ConversationRepository conversationRepository;
    private final MessageService messageService;

    @GetMapping("/users/{userId}")
    public ApiResponse<List<ConversationExportResponse>> conversationsOf(@PathVariable Long userId) {
        return ApiResponse.success(conversationRepository
                .findByParticipantIdsAndDeletedAtIsNullOrderByLastMessageAtDesc(userId).stream()
                .map(c -> new ConversationExportResponse(c.getId(), c.getParticipantIds(), c.getCreatedAt()))
                .toList());
    }

    /** {@code viewerId} is the person asking for their data: the history comes back as they see it. */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<MessagePageResponse> messages(
            @PathVariable String conversationId,
            @RequestParam Long viewerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
        return ApiResponse.success(messageService.exportMessages(conversationId, viewerId, cursor, size));
    }
}
